package com.ozkanmut.ilactakip

import android.content.Context

interface SyncTransport {
    fun flushPendingBlocking(c: Context): Boolean
    fun pullBlocking(c: Context): Boolean
}

object NtfySyncTransport : SyncTransport {
    override fun flushPendingBlocking(c: Context): Boolean =
        Ntfy.flushPendingBlocking(c.applicationContext)

    override fun pullBlocking(c: Context): Boolean =
        SyncEngine.pullNtfyBlocking(c.applicationContext)
}

object SyncTransportRuntime {
    val current: SyncTransport = NtfySyncTransport
}
