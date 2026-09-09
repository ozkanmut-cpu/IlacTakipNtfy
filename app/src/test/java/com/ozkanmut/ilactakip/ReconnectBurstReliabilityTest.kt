package com.ozkanmut.ilactakip

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class ReconnectBurstReliabilityTest {
    private lateinit var c: Context
    private val med = Medication("burst-med", "Burst Med", "1 tablet", listOf("08:00"))

    @Before
    fun setUp() {
        c = ApplicationProvider.getApplicationContext()
        listOf("dosefolk_events", "dosefolk_alert_outbox", "ilac_takip")
            .forEach { c.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        Store.save(c, listOf(med))
    }

    private fun pendingEvent(index: Int) = DoseEvent(
        eventId = "pending-$index",
        type = "alarm",
        time = "08:00",
        actor = "local",
        actorTopic = Store.topic(c),
        timestamp = 1_000L + index,
        medications = listOf(med),
        syncState = "pending",
        revision = index.toLong() + 1L,
        scheduledDate = LocalDate.now().toString(),
        ownerId = Store.topic(c)
    )

    @Test
    fun longOfflineBacklog_drainsOldestPendingEventsFirstAcrossBoundedPasses() {
        repeat(250) { EventStore.append(c, pendingEvent(it)) }

        val first = EventStore.pending(c).take(100)
        assertEquals((0 until 100).map { "pending-$it" }, first.map { it.eventId })
        first.forEach { EventStore.markSynced(c, it.eventId) }

        // New work arrives while reconnect catch-up is still draining.
        repeat(50) { i -> EventStore.append(c, pendingEvent(250 + i)) }

        val second = EventStore.pending(c).take(100)
        assertEquals((100 until 200).map { "pending-$it" }, second.map { it.eventId })
        second.forEach { EventStore.markSynced(c, it.eventId) }

        val third = EventStore.pending(c).take(100)
        assertEquals((200 until 300).map { "pending-$it" }, third.map { it.eventId })
        assertTrue(third.none { it.eventId in first.map { firstEvent -> firstEvent.eventId }.toSet() })
    }

    @Test
    fun caregiverBacklog_usesSmallOldestFirstBatchDuringReconnect() {
        val newestFirst = (24 downTo 0).map { i ->
            PendingAlert("alert-$i", "care", "title", "message-$i", i.toLong())
        }

        val batch = AlertOutbox.batchForFlush(newestFirst)

        assertEquals(10, batch.size)
        assertEquals((0 until 10).map { "alert-$it" }, batch.map { it.id })
    }

    @Test
    fun workerDecision_keepsRetryingWhileEitherDurableBacklogRemains() {
        assertTrue(SyncWorkDecision.shouldRetry(outboundOk = true, alertsOk = true, inboundOk = true, hasMoreOutbound = true))
        assertTrue(SyncWorkDecision.shouldRetry(outboundOk = true, alertsOk = false, inboundOk = true, hasMoreOutbound = false))
    }
}
