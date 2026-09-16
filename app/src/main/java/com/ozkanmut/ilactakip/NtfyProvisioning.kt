package com.ozkanmut.ilactakip

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread

internal data class NtfyEnrollment(val installId: String, val ticket: String)
internal data class NtfyProvisioningCredentials(val gatewayCredential: String, val ntfyToken: String)

object NtfyInstallIdStore {
    private const val PREFS = "dosefolk_ntfy_provisioning"
    private const val INSTALL_ID = "install_id"

    fun load(c: Context): String? = c.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(INSTALL_ID, null)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    fun save(c: Context, installId: String) {
        val value = installId.trim()
        require(NtfyProvisioning.validInstallId(value)) { "invalid installId" }
        val existing = load(c)
        require(existing == null || existing == value) { "installId mismatch" }
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(INSTALL_ID, value)
            .commit()
    }
}

object NtfyProvisioning {
    private const val PROVISION_URL = "https://ntfy.field-maintenance-prod.com/dosefolk-push/v1/provision"
    private val INSTALL_ID_PATTERN = Regex("^[A-Za-z0-9._-]{8,128}$")

    internal fun validInstallId(value: String): Boolean = INSTALL_ID_PATTERN.matches(value)

    internal fun shouldReuseExistingCredentials(
        alreadyProvisioned: Boolean,
        installIdMatches: Boolean,
        gatewayCredentialPresent: Boolean,
        reprovisionRequired: Boolean
    ): Boolean = alreadyProvisioned &&
        installIdMatches &&
        gatewayCredentialPresent &&
        !reprovisionRequired

    internal fun parseEnrollmentUrl(raw: String): NtfyEnrollment? = runCatching {
        val uri = URI(raw)
        if (!uri.scheme.equals("dosefolk", ignoreCase = true) || !uri.host.equals("enroll", ignoreCase = true)) return null
        val values = uri.rawQuery.orEmpty().split('&')
            .filter { it.isNotBlank() }
            .mapNotNull { part ->
                val pieces = part.split('=', limit = 2)
                if (pieces.size != 2) null else decode(pieces[0]) to decode(pieces[1])
            }
            .toMap()
        val installId = values["installId"]?.trim().orEmpty()
        val ticket = values["ticket"]?.trim().orEmpty()
        if (!validInstallId(installId) || ticket.isEmpty() || ticket.length > 256) return null
        NtfyEnrollment(installId, ticket)
    }.getOrNull()

    internal fun decodeProvisioningResponse(body: String, expectedInstallId: String): NtfyProvisioningCredentials? = runCatching {
        val json = JSONObject(body)
        val installId = json.optString("installId").trim()
        val gatewayCredential = json.optString("credential").trim()
        val ntfyToken = json.optString("ntfyToken").trim()
        if (installId != expectedInstallId || gatewayCredential.isEmpty() || ntfyToken.isEmpty()) return null
        NtfyProvisioningCredentials(gatewayCredential, ntfyToken)
    }.getOrNull()

    fun handleEnrollmentUrl(c: Context, raw: String): Boolean {
        val enrollment = parseEnrollmentUrl(raw) ?: return false
        val context = c.applicationContext
        val existing = NtfyInstallIdStore.load(context)
        if (existing != null && existing != enrollment.installId) {
            DosefolkQaLog.record(context, DosefolkQaLog.Category.SECURITY_REJECT, "ntfy_provision_install_mismatch")
            return true
        }
        thread(name = "dosefolk-ntfy-provision", isDaemon = true) {
            val ok = provisionBlocking(context, enrollment)
            DosefolkQaLog.record(
                context,
                if (ok) DosefolkQaLog.Category.SYNC else DosefolkQaLog.Category.ERROR,
                if (ok) "ntfy_provision_success" else "ntfy_provision_failed"
            )
            if (ok) {
                NtfyLiveSync.ensure(context)
                DosefolkSyncScheduler.ensure(context)
                DosefolkSyncScheduler.kick(context)
                SyncEngine.pullOnce(context)
            }
        }
        return true
    }

    @Synchronized
    private fun provisionBlocking(c: Context, enrollment: NtfyEnrollment): Boolean {
        val alreadyProvisioned = NtfyAuth.isProvisioned(c)
        if (alreadyProvisioned) {
            val reprovisionRequired = NtfyAccessRefresh.isReprovisionRequired(c)
            if (shouldReuseExistingCredentials(
                    alreadyProvisioned = true,
                    installIdMatches = NtfyInstallIdStore.load(c) == enrollment.installId,
                    gatewayCredentialPresent = PushGatewayCredentialStore.load(c) != null,
                    reprovisionRequired = reprovisionRequired
                )
            ) {
                return true
            }
            if (!reprovisionRequired) return false
        }
        val connection = URL(PROVISION_URL).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Content-Type", "application/json")
            val localTopic = Store.topic(c)
            val subscriptions = CircleTransport.subscriptionTopics(c)
            val payload = JSONObject()
                .put("installId", enrollment.installId)
                .put("ticket", enrollment.ticket)
                .put("requireNtfyToken", true)
                .put("localTopic", localTopic)
                .put("subscriptions", org.json.JSONArray(subscriptions))
                .toString()
            connection.outputStream.use { it.write(payload.toByteArray(StandardCharsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) {
                connection.errorStream?.close()
                false
            } else {
                val response = connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                val credentials = decodeProvisioningResponse(response, enrollment.installId) ?: return false
                NtfyCredentialStore.save(c, credentials.ntfyToken)
                PushGatewayCredentialStore.save(c, credentials.gatewayCredential)
                NtfyInstallIdStore.save(c, enrollment.installId)
                NtfyAccessRefresh.clearReprovisionRequired(c)
                true
            }
        } catch (_: Exception) {
            false
        } finally {
            connection.disconnect()
        }
    }

    private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}
