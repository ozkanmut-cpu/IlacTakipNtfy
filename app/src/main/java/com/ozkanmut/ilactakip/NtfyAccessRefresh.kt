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

    fun schedule(c: Context) {
        val context = c.applicationContext
        thread(name = "dosefolk-ntfy-access", isDaemon = true) {
            refreshBlocking(context)
        }
    }

    @Synchronized
    fun refreshBlocking(c: Context): Boolean {
        val context = c.applicationContext
        val installId = NtfyInstallIdStore.load(context) ?: return false
        val gatewayCredential = PushGatewayCredentialStore.load(context) ?: return false
        if (!NtfyAuth.isProvisioned(context)) return false

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
                .put("subscriptions", JSONArray(CircleTransport.subscriptionTopics(context)))
                .toString()
            connection.outputStream.use { it.write(payload.toByteArray(StandardCharsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) {
                connection.errorStream?.close()
                DosefolkQaLog.record(
                    context,
                    if (status == 409) DosefolkQaLog.Category.SECURITY_REJECT else DosefolkQaLog.Category.ERROR,
                    if (status == 409) "ntfy_access_reprovision_required" else "ntfy_access_refresh_failed",
                    mapOf("status" to status)
                )
                false
            } else {
                connection.inputStream?.close()
                DosefolkQaLog.record(context, DosefolkQaLog.Category.SYNC, "ntfy_access_refresh_success")
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
}
