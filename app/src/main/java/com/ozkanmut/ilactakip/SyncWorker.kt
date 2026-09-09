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
        // Local maintenance does not need network success and is idempotent.
        SgkStockAutoImporter.reconcile(applicationContext)
        PrescriptionNotifier.evaluate(applicationContext)
        DeliveryLedger.pruneCompleted(applicationContext)

        // Each pass intentionally sends at most one bounded outbound batch. If more
        // durable events remain, Result.retry() schedules the next pass with backoff
        // instead of falsely declaring success and leaving event 101+ stranded until
        // an unrelated future kick or the 15-minute periodic run.
        val outboundOk = Ntfy.flushPendingBlocking(applicationContext)
        val hasMoreOutbound = EventStore.pending(applicationContext).isNotEmpty()
        val alertsOk = AlertOutbox.flushBlocking(applicationContext)
        val inboundOk = SyncEngine.pullBlocking(applicationContext)
        return if (SyncWorkDecision.shouldRetry(outboundOk, alertsOk, inboundOk, hasMoreOutbound)) Result.retry() else Result.success()
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
        val request = OneTimeWorkRequestBuilder<DosefolkSyncWorker>()
            .setConstraints(network())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(c.applicationContext)
            .enqueueUniqueWork(KICK, ExistingWorkPolicy.KEEP, request)
    }
}
