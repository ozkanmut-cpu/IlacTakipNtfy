package com.ozkanmut.ilactakip

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class DeliveryLedgerCrashRecoveryTest {
    private lateinit var c: Context
    private val med = Medication("ledger-med", "Ledger Med", "1 tablet", listOf("08:00"))

    @Before
    fun setUp() {
        c = ApplicationProvider.getApplicationContext()
        listOf("dosefolk_events", "dosefolk_delivery_ledger", "ilac_takip").forEach {
            c.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    private fun event(id: String, syncState: String) = DoseEvent(
        eventId = id,
        type = "taken",
        time = "08:00",
        actor = "local",
        actorTopic = "local-topic",
        timestamp = System.currentTimeMillis(),
        medications = listOf(med),
        syncState = syncState,
        revision = 1L,
        scheduledDate = LocalDate.now().toString(),
        ownerId = ""
    )

    @Test
    fun pruneCompleted_preservesPendingReceipt_butRemovesSyncedResidue() {
        val id = "crash-window-event"
        EventStore.append(c, event(id, "pending"))
        DeliveryLedger.markDelivered(c, id, "care-a")
        assertTrue(DeliveryLedger.delivered(c, id, "care-a"))

        DeliveryLedger.pruneCompleted(c)
        assertTrue(DeliveryLedger.delivered(c, id, "care-a"))

        EventStore.markSynced(c, id)
        DeliveryLedger.pruneCompleted(c)

        assertFalse(DeliveryLedger.delivered(c, id, "care-a"))
        assertTrue(EventStore.pending(c).none { it.eventId == id })
    }

    @Test
    fun publisherTopicMigration_virtualDeliveryOnlyAppliesToActiveCirclePeers() {
        val id = "legacy-pending-event"
        EventStore.append(c, event(id, "pending"))
        Store.savePeople(c, listOf(Person("peer-1", "Care A", "care-a")))

        assertTrue(DeliveryLedger.delivered(c, id, "care-a"))
        assertFalse(DeliveryLedger.delivered(c, id, "removed-peer"))
    }
}
