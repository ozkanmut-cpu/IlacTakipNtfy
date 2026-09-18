package com.ozkanmut.ilactakip

import android.content.Context

/** Injectable relay adapter. SyncTransportRuntime deliberately does not select it until Task 16. */
class RelaySyncTransport(private val outbox: RelayOutbox, private val inbox: RelayInbox) : SyncTransport {
    override fun flushPendingBlocking(c: Context): Boolean = try { outbox.flush() } catch (_: Exception) { false }
    override fun pullBlocking(c: Context): Boolean = try { inbox.reconcile() } catch (_: Exception) { false }

    companion object {
        /** Assembly only; runtime selection remains NtfySyncTransport until the Task 16 cutover. */
        fun production(context: Context, baseUrl: String = RelayApi.PRODUCTION_URL): RelaySyncTransport? {
            return try {
                val installId = NtfyInstallIdStore.load(context) ?: return null
                val api = RelayApi.production(context, baseUrl) ?: return null
                val identities = RelayIdentityStore(context)
                val crypto = RelayCrypto(identities, installId)
                val peers = RelayPeerStore(context)
                RelaySyncTransport(RelayOutbox(context, installId, crypto, peers, api), RelayInbox(context, installId, crypto, peers, api))
            } catch (_: Exception) { null }
        }
    }
}
