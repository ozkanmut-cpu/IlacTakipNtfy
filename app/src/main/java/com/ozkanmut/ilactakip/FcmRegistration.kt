package com.ozkanmut.ilactakip

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.android.gms.tasks.Tasks
import com.google.firebase.installations.FirebaseInstallations
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal enum class FcmRegistrationLifecycleDecision(val preserveNtfyProvisioning: Boolean = true) {
    NOOP,
    RETRY_FIREBASE,
    CONTINUE
}

internal object FcmRegistrationPolicy {
    fun shouldSchedule(
        isProvisioned: Boolean,
        hasInstallId: Boolean,
        hasGatewayCredential: Boolean
    ): Boolean = isProvisioned && hasInstallId && hasGatewayCredential

    fun lifecycleDecision(
        isProvisioned: Boolean,
        hasInstallId: Boolean,
        hasGatewayCredential: Boolean,
        firebaseTargetAvailable: Boolean
    ): FcmRegistrationLifecycleDecision {
        if (!shouldSchedule(isProvisioned, hasInstallId, hasGatewayCredential)) {
            return FcmRegistrationLifecycleDecision.NOOP
        }
        if (!firebaseTargetAvailable) return FcmRegistrationLifecycleDecision.RETRY_FIREBASE
        return FcmRegistrationLifecycleDecision.CONTINUE
    }

    fun shouldRegister(
        isProvisioned: Boolean,
        hasInstallId: Boolean,
        hasGatewayCredential: Boolean,
        tokenPresent: Boolean,
        tokenHashChanged: Boolean,
        stale: Boolean
    ): Boolean = isProvisioned &&
        hasInstallId &&
        hasGatewayCredential &&
        tokenPresent &&
        (tokenHashChanged || stale)
}

private object FcmRegistrationStateStore {
    private const val PREFS = "dosefolk_fcm_registration"
    private const val TARGET_HASH = "target_sha256_v1"
    private const val LAST_SUCCESS_MS = "last_success_ms_v1"
    private const val STALE_AFTER_MS = 7L * 24 * 60 * 60 * 1000

    data class State(val targetHash: String?, val lastSuccessMs: Long) {
        fun isStale(nowMs: Long): Boolean = lastSuccessMs <= 0 || nowMs - lastSuccessMs >= STALE_AFTER_MS
    }

    fun load(c: Context): State {
        val prefs = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return State(
            targetHash = prefs.getString(TARGET_HASH, null),
            lastSuccessMs = prefs.getLong(LAST_SUCCESS_MS, 0L)
        )
    }

    fun markSuccess(c: Context, targetHash: String, nowMs: Long) {
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(TARGET_HASH, targetHash)
            .putLong(LAST_SUCCESS_MS, nowMs)
            .apply()
    }
}

private object FirebaseInstallationTarget {
    fun currentBlocking(): String? = runCatching {
        Tasks.await(FirebaseInstallations.getInstance().id, 20, TimeUnit.SECONDS)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }.getOrNull()
}

class FcmRegistrationWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {
    override fun doWork(): Result {
        val context = applicationContext
        val installId = NtfyInstallIdStore.load(context)
        val gatewayCredential = PushGatewayCredentialStore.load(context)
        val isProvisioned = NtfyAuth.isProvisioned(context)

        if (!FcmRegistrationPolicy.shouldSchedule(
                isProvisioned = isProvisioned,
                hasInstallId = installId != null,
                hasGatewayCredential = gatewayCredential != null
            )
        ) {
            return Result.success()
        }

        val pushTarget = FirebaseInstallationTarget.currentBlocking()
        when (
            FcmRegistrationPolicy.lifecycleDecision(
                isProvisioned = isProvisioned,
                hasInstallId = installId != null,
                hasGatewayCredential = gatewayCredential != null,
                firebaseTargetAvailable = pushTarget != null
            )
        ) {
            FcmRegistrationLifecycleDecision.NOOP -> return Result.success()
            FcmRegistrationLifecycleDecision.RETRY_FIREBASE -> return Result.retry()
            FcmRegistrationLifecycleDecision.CONTINUE -> Unit
        }

        val safeInstallId = installId ?: return Result.success()
        val safeGatewayCredential = gatewayCredential ?: return Result.success()
        val safePushTarget = pushTarget ?: return Result.retry()
        val targetHash = sha256(safePushTarget)
        val state = FcmRegistrationStateStore.load(context)
        val nowMs = System.currentTimeMillis()
        val shouldRegister = FcmRegistrationPolicy.shouldRegister(
            isProvisioned = isProvisioned,
            hasInstallId = true,
            hasGatewayCredential = true,
            tokenPresent = safePushTarget.isNotEmpty(),
            tokenHashChanged = state.targetHash != targetHash,
            stale = state.isStale(nowMs)
        )
        if (!shouldRegister) return Result.success()

        return registerBlocking(
            context = context,
            installId = safeInstallId,
            gatewayCredential = safeGatewayCredential,
            pushTarget = safePushTarget,
            targetHash = targetHash,
            nowMs = nowMs
        )
    }

    private fun registerBlocking(
        context: Context,
        installId: String,
        gatewayCredential: String,
        pushTarget: String,
        targetHash: String,
        nowMs: Long
    ): Result {
        val connection = URL(REGISTER_URL).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $gatewayCredential")

            val payload = JSONObject()
                .put("installId", installId)
                .put("platform", "android")
                .put("pushToken", pushTarget)
                .put("localTopic", Store.topic(context))
                .put("subscriptions", JSONArray(CircleTransport.subscriptionTopics(context)))
                .toString()
            connection.outputStream.use { it.write(payload.toByteArray(StandardCharsets.UTF_8)) }

            when (val status = connection.responseCode) {
                204 -> {
                    connection.inputStream?.close()
                    FcmRegistrationStateStore.markSuccess(context, targetHash, nowMs)
                    DosefolkQaLog.record(context, DosefolkQaLog.Category.SYNC, "fcm_registration_success")
                    Result.success()
                }
                409 -> {
                    connection.errorStream?.close()
                    NtfyAccessRefresh.markReprovisionRequired(context)
                    DosefolkQaLog.record(context, DosefolkQaLog.Category.SECURITY_REJECT, "fcm_registration_reprovision_required")
                    Result.success()
                }
                408, 425, 429 -> {
                    connection.errorStream?.close()
                    DosefolkQaLog.record(context, DosefolkQaLog.Category.ERROR, "fcm_registration_retry", mapOf("status" to status))
                    Result.retry()
                }
                in 500..599 -> {
                    connection.errorStream?.close()
                    DosefolkQaLog.record(context, DosefolkQaLog.Category.ERROR, "fcm_registration_retry", mapOf("status" to status))
                    Result.retry()
                }
                else -> {
                    connection.errorStream?.close()
                    DosefolkQaLog.record(context, DosefolkQaLog.Category.ERROR, "fcm_registration_rejected", mapOf("status" to status))
                    Result.failure()
                }
            }
        } catch (e: Exception) {
            DosefolkQaLog.record(
                context,
                DosefolkQaLog.Category.ERROR,
                "fcm_registration_exception",
                mapOf("error" to e.javaClass.simpleName)
            )
            Result.retry()
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val REGISTER_URL = "https://ntfy.field-maintenance-prod.com/dosefolk-push/v1/register"

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

object FcmRegistrationScheduler {
    private const val UNIQUE_WORK = "dosefolk-fcm-registration"

    private fun request() = OneTimeWorkRequestBuilder<FcmRegistrationWorker>()
        .setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
        )
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .build()

    fun ensure(c: Context) {
        WorkManager.getInstance(c.applicationContext)
            .enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.REPLACE, request())
    }

    fun refresh(c: Context) = ensure(c)
}
