package com.ozkanmut.ilactakip

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class RemoteCrashReplayTest {
    private lateinit var c: Context
    private val med = Medication("crash-med", "Crash Med", "1 tablet", listOf("08:00"))
    private val date get() = LocalDate.now().toString()

    @Before
    fun setUp() {
        c = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(c)
        listOf(
            "ilac_takip",
            "dosefolk_events",
            "dosefolk_remote_event_receipts",
            "dosefolk_stock",
            "dosefolk_owner_scope",
            "dosefolk_attention_budget",
            "dosefolk_care_baton",
            "dosefolk_alarm_scheduler",
            "dosefolk_prn_usage"
        ).forEach { c.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        Store.save(c, listOf(med))
        StockEngine.configure(c, med, packSize = 10, currentDoses = 10, lowThreshold = 2)
    }

    private fun remote(id: String, type: String, snoozeUntil: Long = 0L) = DoseEvent(
        eventId = id,
        type = type,
        time = "08:00",
        actor = "Remote caregiver",
        actorTopic = "remote-device",
        timestamp = System.currentTimeMillis(),
        medications = listOf(med),
        syncState = "synced",
        revision = 2L,
        scheduledDate = date,
        snoozeUntil = snoozeUntil,
        ownerId = Store.topic(c)
    )

    /** Simulates a crash after durable event persistence and stock side effect but before receipt. */
    @Test
    fun replayAfterCrash_doesNotDoubleConsumeStock_andCompletesTerminalCleanup() {
        val event = remote("remote-crash-taken", "taken")

        EventStore.append(c, event)
        OwnerScopeStore.remember(c, event)
        StockEngine.applyEvent(c, event)
        assertEquals(9, StockEngine.forMedication(c, med.id)?.remainingDoses)
        assertFalse(RemoteEventReceiptStore.processed(c, event.eventId))

        // Replay after process restart: idempotent stock + remaining UI/lifecycle cleanup.
        EventStore.append(c, event)
        OwnerScopeStore.remember(c, event)
        StockEngine.applyEvent(c, event)
        SyncEngine.applyRemoteState(c, event)
        RemoteEventReceiptStore.markProcessed(c, event.eventId)

        assertEquals(9, StockEngine.forMedication(c, med.id)?.remainingDoses)
        assertTrue(RemoteEventReceiptStore.processed(c, event.eventId))
        assertEquals(DoseSessionStatus.TAKEN, DoseStateEngine.stateForTime(c, "08:00", LocalDate.now()).status)
        val manager = c.getSystemService(NotificationManager::class.java)
        assertEquals(0, manager.activeNotifications.count { it.id == ("group-$date-08:00").hashCode() })
    }

    /** Replaying an unfinished remote snooze must keep exactly the same logical snooze deadline. */
    @Test
    fun replayAfterCrash_remoteSnoozeKeepsSameDeadlineAndRemainsSnoozed() {
        val deadline = System.currentTimeMillis() + 30 * 60_000L
        val event = remote("remote-crash-snooze", "snoozed", deadline)

        EventStore.append(c, event)
        StockEngine.applyEvent(c, event)
        assertFalse(RemoteEventReceiptStore.processed(c, event.eventId))

        SyncEngine.applyRemoteState(c, event)
        SyncEngine.applyRemoteState(c, event)
        RemoteEventReceiptStore.markProcessed(c, event.eventId)

        val state = DoseStateEngine.stateForTime(c, "08:00", LocalDate.now())
        assertEquals(DoseSessionStatus.SNOOZED, state.status)
        assertEquals(deadline, state.latestEvent?.snoozeUntil)
        assertTrue(RemoteEventReceiptStore.processed(c, event.eventId))
    }
}
