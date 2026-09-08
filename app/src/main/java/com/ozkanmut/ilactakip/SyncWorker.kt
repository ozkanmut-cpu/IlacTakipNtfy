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

class DosefolkSyncWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {
    override fun doWork(): Result {
        val outboundOk = Ntfy.flushPendingBlocking(applicationContext)
        val inboundOk = SyncEngine.pullBlocking(applicationContext)
        return if (outboundOk && inboundOk) Result.success() else Result.retry()
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
            .enqueueUniqueWork(KICK, ExistingWorkPolicy.REPLACE, request)
    }
}
