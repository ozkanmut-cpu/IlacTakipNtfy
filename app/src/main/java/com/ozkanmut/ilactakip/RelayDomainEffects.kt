package com.ozkanmut.ilactakip

import android.content.Context
import java.io.IOException

/** Checked, replay-safe boundary for every domain mutation reached by the relay inbox. */
internal object RelayDomainEffects {
    fun apply(c: Context, event: DoseEvent) {
        requireDurable(OwnerScopeStore.rememberChecked(c, event))
        val ownerId = event.ownerId.ifBlank { event.actorTopic }
        if (ownerId.isNotBlank() && ownerId != OwnerScopeStore.localOwnerId(c)) {
            event.medicationMeta.forEach { meta ->
                requireDurable(MedicationMetaStore.saveRemoteChecked(c, ownerId, meta))
            }
        }
        if (ownerId.isBlank() || ownerId == OwnerScopeStore.localOwnerId(c)) {
            requireDurable(PrnUsageLedger.observeChecked(c, event))
            requireDurable(StockEngine.applyEventChecked(c, event))
        }
        requireDurable(SyncEngine.applyRemoteStateChecked(c, event))
        RemoteEventReceiptStore.markProcessed(c, event.eventId)
    }

    private fun requireDurable(committed: Boolean) {
        if (!committed) throw IOException("Relay domain effect persistence failed")
    }
}
