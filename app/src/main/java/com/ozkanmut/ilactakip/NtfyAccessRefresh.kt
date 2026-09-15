package com.ozkanmut.ilactakip

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread

object NtfyAccessRefresh {
    private const val ACCESS_URL = "https://ntfy.field-maintenance-prod.com/dosefolk-push/v1/access"
    private const val PREFS = "dosefolk_ntfy_access_refresh"
    private const val LAST_SUBSCRIPTIONS = "last_subscriptions_v1"
    private const val REPROVISION_REQUIRED = "reprovision_required_v1"

    fun schedule(c: Context, force: Boolean = false) {
        val context = c.applicationContext
        thread(name = "dosefolk-ntfy-access", isDaemon = true) {
            refreshBlocking(context, force)
        }
    }

    fun isReprovisionRequired(c: Context): Boolean =
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(REPROVISION_REQUIRED, false)

    @Synchronized
    fun refreshBlocking(c: Context, force: Boolean = false): Boolean {
        val context = c.applicationContext
        val installId = NtfyInstallIdStore.load(context) ?: return false
        val gatewayCredential = PushGatewayCredentialStore.load(context) ?: return false
        if (!NtfyAuth.isProvisioned(context)) return false

        val subscriptions = normalizedSubscriptions(context)
        val fingerprint = subscriptions.joinToString("\n")
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!force && !prefs.getBoolean(REPROVISION_REQUIRED, false) &&
            prefs.getString(LAST_SUBSCRIPTIONS, null) == fingerprint) {
            DosefolkQaLog.record(context, DosefolkQaLog.Category.SYNC, "ntfy_access_refresh_unchanged")
            return true
        }

        val connection = URL(ACCESS_URL).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $gatewayCredential")
            val payload = JSONObject()
                .put("installId", installId)
                .put("subscriptions", JSONArray(subscriptions))
                .toString()
            connection.outputStream.use { it.write(payload.toByteArray(StandardCharsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) {
                connection.errorStream?.close()
                if (status == 409) {
                    prefs.edit().putBoolean(REPROVISION_REQUIRED, true).apply()
                }
                DosefolkQaLog.record(
                    context,
                    if (status == 409) DosefolkQaLog.Category.SECURITY_REJECT else DosefolkQaLog.Category.ERROR,
                    if (status == 409) "ntfy_access_reprovision_required" else "ntfy_access_refresh_failed",
                    mapOf("status" to status)
                )
                false
            } else {
                connection.inputStream?.close()
                prefs.edit()
                    .putString(LAST_SUBSCRIPTIONS, fingerprint)
                    .putBoolean(REPROVISION_REQUIRED, false)
                    .apply()
                DosefolkQaLog.record(
                    context,
                    DosefolkQaLog.Category.SYNC,
                    "ntfy_access_refresh_success",
                    mapOf("forced" to force, "topicCount" to subscriptions.size)
                )
                true
            }
        } catch (e: Exception) {
            DosefolkQaLog.record(
                context,
                DosefolkQaLog.Category.ERROR,
                "ntfy_access_refresh_exception",
                mapOf("error" to e.javaClass.simpleName)
            )
            false
        } finally {
            connection.disconnect()
        }
    }

    private fun normalizedSubscriptions(c: Context): List<String> =
        CircleTransport.subscriptionTopics(c).map(String::trim).filter(String::isNotEmpty).distinct().sorted()
}
