package com.ozkanmut.ilactakip

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class TwoDeviceOfflineRaceTest {
    private lateinit var c: Context
    private val med = Medication("med-race", "Race Med", "1 tablet", listOf("08:00"))
    private val date = LocalDate.now().toString()

    @Before
    fun setUp() {
        c = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(c)
        listOf(
            "ilac_takip",
            "dosefolk_events",
            "dosefolk_program_rules",
            "dosefolk_owner_scope",
            "dosefolk_alarm_scheduler",
            "dosefolk_attention_budget",
            "dosefolk_care_baton",
            "dosefolk_undo_recovery",
            "dosefolk_remote_event_receipts",
            "dosefolk_delivery_ledger",
            "dosefolk_alert_outbox",
            "dosefolk_stock"
        ).forEach { c.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        Store.save(c, listOf(med))
    }

    private fun event(
        id: String,
        type: String,
        actorTopic: String,
        timestamp: Long,
        revision: Long,
        syncState: String = "synced"
    ) = DoseEvent(
        eventId = id,
        type = type,
        time = "08:00",
        actor = actorTopic,
        actorTopic = actorTopic,
        timestamp = timestamp,
        medications = listOf(med),
        syncState = syncState,
        revision = revision,
        scheduledDate = date,
        ownerId = Store.topic(c)
    )

    @Test
    fun offlineLocalTaken_plusRemoteMissed_createsConflictAndSurvivesBoot() {
        val base = System.currentTimeMillis()
        val local = event("local-taken", "taken", Store.topic(c), base, 1L, syncState = "pending")
        val remote = event("remote-missed", "missed", "phone-b", base + 30_000L, 7L)

        EventStore.append(c, local)
        EventStore.append(c, remote)

        assertEquals(DoseSessionStatus.CONFLICT, DoseStateEngine.stateForTime(c, "08:00").status)
        assertTrue(EventStore.pending(c).any { it.eventId == "local-taken" })

        BootReceiver().onReceive(c, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertEquals(DoseSessionStatus.CONFLICT, DoseStateEngine.stateForTime(c, "08:00").status)
        assertTrue(EventStore.pending(c).any { it.eventId == "local-taken" })
    }

    @Test
    fun replayOfSameRemoteEvent_isIdempotent() {
        val base = System.currentTimeMillis()
        val local = event("local-taken", "taken", Store.topic(c), base, 1L, syncState = "pending")
        val remote = event("remote-missed", "missed", "phone-b", base + 30_000L, 7L)

        EventStore.append(c, local)
        EventStore.append(c, remote)
        EventStore.append(c, remote.copy(syncState = "pending"))

        assertEquals(2, EventStore.load(c).count { it.time == "08:00" })
        assertEquals(1, EventStore.load(c).count { it.eventId == "remote-missed" })
        assertEquals(DoseSessionStatus.CONFLICT, DoseStateEngine.stateForTime(c, "08:00").status)
    }

    @Test
    fun explicitResolution_survivesLateReplayOfOlderRemoteEvent() {
        val base = System.currentTimeMillis()
        val local = event("local-taken", "taken", Store.topic(c), base, 1L, syncState = "pending")
        val remote = event("remote-missed", "missed", "phone-b", base + 30_000L, 7L)
        val resolution = event("resolve-taken", "conflict_resolved_taken", Store.topic(c), base + 60_000L, 2L)

        EventStore.append(c, local)
        EventStore.append(c, remote)
        EventStore.append(c, resolution)
        EventStore.append(c, remote)

        val state = DoseStateEngine.stateForTime(c, "08:00")
        assertEquals(DoseSessionStatus.TAKEN, state.status)
        assertEquals("resolve-taken", state.latestEvent?.eventId)
        assertEquals(3, EventStore.load(c).count { it.time == "08:00" })
    }

    @Test
    fun delayedOppositeRemoteTerminal_remainsConflictDespiteClockDistance() {
        val base = System.currentTimeMillis()
        val remoteOld = event("remote-old-missed", "missed", "phone-b", base, 9L)
        val localNew = event("local-new-taken", "taken", Store.topic(c), base + 5 * 60_000L, 2L, syncState = "pending")

        EventStore.append(c, localNew)
        EventStore.append(c, remoteOld)

        val state = DoseStateEngine.stateForTime(c, "08:00")
        assertEquals(DoseSessionStatus.CONFLICT, state.status)
        assertEquals(2, state.conflictEvents.size)
    }

    @Test
    fun sameDoseTakenOnTwoDevices_consumesStockOnlyOnce() {
        val base = System.currentTimeMillis()
        StockEngine.configure(c, med, packSize = 10, currentDoses = 10, lowThreshold = 2)
        val localTaken = event("local-taken-stock", "taken", Store.topic(c), base, 1L, syncState = "pending")
        val remoteTaken = event("remote-taken-stock", "taken", "phone-b", base + 1_000L, 7L)

        StockEngine.applyEvent(c, localTaken)
        StockEngine.applyEvent(c, remoteTaken)

        assertEquals(9, StockEngine.forMedication(c, med.id)?.remainingDoses)
    }

    @Test
    fun undoAfterCrossDeviceDuplicateTaken_restoresStockOnlyOnce() {
        val base = System.currentTimeMillis()
        StockEngine.configure(c, med, packSize = 10, currentDoses = 10, lowThreshold = 2)
        val localTaken = event("local-taken-stock", "taken", Store.topic(c), base, 1L, syncState = "pending")
        val remoteTaken = event("remote-taken-stock", "taken", "phone-b", base + 1_000L, 7L)
        val undo = event("undo-stock", "undo_taken", Store.topic(c), base + 2_000L, 8L, syncState = "pending")

        StockEngine.applyEvent(c, localTaken)
        StockEngine.applyEvent(c, remoteTaken)
        StockEngine.applyEvent(c, undo)
        StockEngine.applyEvent(c, undo.copy(eventId = "undo-stock-replay"))

        assertEquals(10, StockEngine.forMedication(c, med.id)?.remainingDoses)
    }
}
