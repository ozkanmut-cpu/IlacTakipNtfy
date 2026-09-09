package com.ozkanmut.ilactakip

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class DeliveryLedgerRetentionTest {
    private lateinit var c: Context
    private val med = Medication("ledger-med", "Ledger Med", "1 tablet", listOf("08:00"))

    @Before
    fun setUp() {
        c = ApplicationProvider.getApplicationContext()
        listOf("ilac_takip", "dosefolk_events", "dosefolk_delivery_ledger").forEach {
            c.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        Store.save(c, listOf(med))
    }

    @Test
    fun pendingEventReceipt_survivesLedgerCompaction() {
        val pendingId = "pending-protected"
        EventStore.append(
            c,
            DoseEvent(
                eventId = pendingId,
                type = "taken",
                time = "08:00",
                actor = "local",
                actorTopic = Store.topic(c),
                timestamp = System.currentTimeMillis(),
                medications = listOf(med),
                syncState = "pending",
                revision = 1L,
                scheduledDate = LocalDate.now().toString(),
                ownerId = Store.topic(c)
            )
        )
        DeliveryLedger.markDelivered(c, pendingId, "care-protected")
        assertTrue(DeliveryLedger.delivered(c, pendingId, "care-protected"))

        // Fill the bounded ledger with receipts belonging to already-finished events.
        repeat(4_100) { i ->
            DeliveryLedger.markDelivered(c, "done-$i", "care-$i")
        }

        // Another insert triggers compaction. The active pending event receipt must remain.
        DeliveryLedger.markDelivered(c, "trigger", "care-trigger")

        assertTrue(DeliveryLedger.delivered(c, pendingId, "care-protected"))
    }
}
