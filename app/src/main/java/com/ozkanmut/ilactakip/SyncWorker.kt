package com.ozkanmut.ilactakip

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

internal object SyncWorkDecision {
    fun shouldRetry(outboundOk: Boolean, alertsOk: Boolean, inboundOk: Boolean, hasMoreOutbound: Boolean): Boolean =
        !outboundOk || !alertsOk || !inboundOk || hasMoreOutbound
}

class DosefolkSyncWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {
    override fun doWork(): Result {
        DosefolkQaLog.record(applicationContext, DosefolkQaLog.Category.WORKER, "sync_worker_start")
        SgkStockAutoImporter.reconcile(applicationContext)
        PrescriptionNotifier.evaluate(applicationContext)
        DeliveryLedger.pruneCompleted(applicationContext)

        val outboundOk = Ntfy.flushPendingBlocking(applicationContext)
        val hasMoreOutbound = EventStore.pending(applicationContext).isNotEmpty()
        val alertsOk = AlertOutbox.flushBlocking(applicationContext)
        val inboundOk = SyncEngine.pullBlocking(applicationContext)
        val retry = SyncWorkDecision.shouldRetry(outboundOk, alertsOk, inboundOk, hasMoreOutbound)
        DosefolkQaLog.record(
            applicationContext,
            DosefolkQaLog.Category.WORKER,
            if (retry) "sync_worker_retry" else "sync_worker_success",
            mapOf(
                "outboundOk" to outboundOk,
                "alertsOk" to alertsOk,
                "inboundOk" to inboundOk,
                "hasMoreOutbound" to hasMoreOutbound
            )
        )
        return if (retry) Result.retry() else Result.success()
    }
}

object DosefolkSyncScheduler {
    private const val PERIODIC = "dosefolk-periodic-sync"
    private const val KICK = "dosefolk-sync-kick"

    private fun network() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun ensure(c: Context) {
        val request = PeriodicWorkRequestBuilder<DosefolkSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(network())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(c.applicationContext)
            .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun kick(c: Context) {
        DosefolkQaLog.record(c, DosefolkQaLog.Category.WORKER, "sync_worker_kick")
        val request = OneTimeWorkRequestBuilder<DosefolkSyncWorker>()
            .setConstraints(network())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(c.applicationContext)
            .enqueueUniqueWork(KICK, ExistingWorkPolicy.KEEP, request)
    }
}
