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

        val transport = SyncWorkerTransport.selectedForOutboundAndInbound()
        val outboundOk = transport.flushPendingBlocking(applicationContext)
        val hasMoreOutbound = EventStore.pending(applicationContext).isNotEmpty()
        val alertsOk = AlertOutbox.flushBlocking(applicationContext)
        val inboundOk = transport.pullBlocking(applicationContext)
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

/** Ensures worker inbound and outbound paths share exactly one selected transport. */
internal object SyncWorkerTransport {
    fun selectedForOutboundAndInbound(): SyncTransport = SyncTransportRuntime.current
}

object DosefolkSyncScheduler {
    private const val PERIODIC = "dosefolk-periodic-sync"
    private const val KICK = "dosefolk-sync-kick"
    private const val FULL_RECONCILIATION = "dosefolk-full-reconciliation"

    private fun network() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    private fun oneTimeRequest() = OneTimeWorkRequestBuilder<DosefolkSyncWorker>()
        .setConstraints(network())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
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
        WorkManager.getInstance(c.applicationContext)
            .enqueueUniqueWork(KICK, ExistingWorkPolicy.KEEP, oneTimeRequest())
    }

    fun fullReconciliation(c: Context) {
        DosefolkQaLog.record(c, DosefolkQaLog.Category.WORKER, "sync_worker_full_reconciliation")
        WorkManager.getInstance(c.applicationContext)
            .enqueueUniqueWork(FULL_RECONCILIATION, ExistingWorkPolicy.REPLACE, oneTimeRequest())
    }
}
