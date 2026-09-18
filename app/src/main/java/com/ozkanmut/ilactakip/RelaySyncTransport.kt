package com.ozkanmut.ilactakip

import android.content.Context

/** Injectable relay adapter. SyncTransportRuntime deliberately does not select it until Task 16. */
class RelaySyncTransport(private val outbox: RelayOutbox, private val inbox: RelayInbox) : SyncTransport {
    override fun flushPendingBlocking(c: Context): Boolean = try { outbox.flush() } catch (_: Exception) { false }
    override fun pullBlocking(c: Context): Boolean = try { inbox.reconcile() } catch (_: Exception) { false }

}
