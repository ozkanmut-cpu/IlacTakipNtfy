package com.ozkanmut.ilactakip

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class RevocationCleanupReliabilityTest {
    private lateinit var c: Context
    private val med = Medication("med-revoke", "Revoke Med", "1 tablet", listOf("08:00"))
    private val date = LocalDate.now().toString()

    @Before
    fun setUp() {
        c = ApplicationProvider.getApplicationContext()
        listOf(
            "dosefolk_events",
            "dosefolk_delivery_ledger",
            "dosefolk_care_baton",
            "dosefolk_owner_scope",
            "dosefolk_remote_capabilities",
            "dosefolk_capability_order",
            "dosefolk_attention_budget"
        ).forEach { c.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        Store.save(c, listOf(med))
    }

    private fun pendingEvent(id: String) = DoseEvent(
        eventId = id,
        type = "alarm",
        time = "08:00",
        actor = "local",
        actorTopic = Store.topic(c),
        timestamp = System.currentTimeMillis(),
        medications = listOf(med),
        syncState = "pending",
        revision = 1L,
        scheduledDate = date,
        ownerId = Store.topic(c)
    )

    @Test
    fun deterministicAlarmReceipt_withPipes_survivesCompactionWhilePending() {
        val eventId = "group-08:00|123456789"
        EventStore.append(c, pendingEvent(eventId))
        DeliveryLedger.markDelivered(c, eventId, "care-a")

        val prefs = c.getSharedPreferences("dosefolk_delivery_ledger", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        repeat(4100) { editor.putBoolean("old-$it|topic-$it", true) }
        editor.commit()

        DeliveryLedger.markDelivered(c, "new-event", "new-topic")

        assertTrue(DeliveryLedger.delivered(c, eventId, "care-a"))
    }

    @Test
    fun revokePeer_clearsDeliveryReceiptsAndRemoteCareBatonClaim() {
        val peer = "care-old"
        val eventId = "group-08:00|987654321"
        EventStore.append(c, pendingEvent(eventId))
        DeliveryLedger.markDelivered(c, eventId, peer)
        DeliveryLedger.markDelivered(c, eventId, "care-keep")

        val claim = JSONObject()
            .put("doseKey", "$date|08:00")
            .put("time", "08:00")
            .put("actor", "Old caregiver")
            .put("actorTopic", peer)
            .put("claimedAt", System.currentTimeMillis())
            .put("expiresAt", System.currentTimeMillis() + 30 * 60_000L)
            .put("scheduledDate", date)
        c.getSharedPreferences("dosefolk_care_baton", Context.MODE_PRIVATE)
            .edit().putString("claims", JSONArray().put(claim).toString()).commit()

        RevocationCleanup.clearPeer(c, peer)

        assertFalse(DeliveryLedger.delivered(c, eventId, peer))
        assertTrue(DeliveryLedger.delivered(c, eventId, "care-keep"))
        assertTrue(CareBatonStore.load(c).none { it.actorTopic == peer })
    }
}
