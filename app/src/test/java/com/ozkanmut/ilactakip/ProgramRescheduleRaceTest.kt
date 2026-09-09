package com.ozkanmut.ilactakip

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class ProgramRescheduleRaceTest {
    private lateinit var c: Context
    private val med = Medication("med-race", "Race Med", "1 tablet", listOf("08:00"))

    @Before
    fun setUp() {
        c = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(c)
        listOf(
            "ilac_takip",
            "dosefolk_events",
            "dosefolk_alarm_scheduler",
            "dosefolk_program_rules",
            "dosefolk_program_sync",
            "dosefolk_owner_scope"
        ).forEach { c.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        Store.save(c, listOf(med))
    }

    private fun existingGroup(time: String): PendingIntent? = PendingIntent.getBroadcast(
        c,
        "group-$time".hashCode(),
        Intent(c, AlarmReceiver::class.java),
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
    )

    private fun remoteProgramEvent(
        eventId: String,
        actorTopic: String,
        revision: Long,
        timestamp: Long,
        medication: Medication,
        ownerId: String = "remote-owner"
    ) = DoseEvent(
        eventId = eventId,
        type = "program_updated",
        time = medication.times.firstOrNull() ?: "program",
        actor = actorTopic,
        actorTopic = actorTopic,
        timestamp = timestamp,
        medications = listOf(medication),
        syncState = "synced",
        revision = revision,
        ownerId = ownerId
    )

    @Test
    fun movingMedication_replacesOldAlarmWithNewAlarm() {
        AlarmScheduler.scheduleAll(c, Store.load(c), observeProgramChanges = false)
        assertTrue(existingGroup("08:00") != null)

        Store.save(c, listOf(med.copy(times = listOf("09:00"))))
        AlarmScheduler.scheduleAll(c, Store.load(c), observeProgramChanges = false)

        assertNull(existingGroup("08:00"))
        assertTrue(existingGroup("09:00") != null)

        val scheduled = c.getSharedPreferences("dosefolk_alarm_scheduler", Context.MODE_PRIVATE)
            .getStringSet("scheduled_times", emptySet())
            .orEmpty()
        assertEquals(setOf("09:00"), scheduled)
    }

    @Test
    fun repeatedReschedule_doesNotLeaveGhostAlarmKeys() {
        AlarmScheduler.scheduleAll(c, Store.load(c), observeProgramChanges = false)
        Store.save(c, listOf(med.copy(times = listOf("09:00"))))
        AlarmScheduler.scheduleAll(c, Store.load(c), observeProgramChanges = false)
        Store.save(c, listOf(med.copy(times = listOf("10:00"))))
        AlarmScheduler.scheduleAll(c, Store.load(c), observeProgramChanges = false)

        assertNull(existingGroup("08:00"))
        assertNull(existingGroup("09:00"))
        assertTrue(existingGroup("10:00") != null)

        val scheduled = c.getSharedPreferences("dosefolk_alarm_scheduler", Context.MODE_PRIVATE)
            .getStringSet("scheduled_times", emptySet())
            .orEmpty()
        assertEquals(setOf("10:00"), scheduled)
    }

    @Test
    fun removingMedication_cancelsItsAlarmCompletely() {
        AlarmScheduler.scheduleAll(c, Store.load(c), observeProgramChanges = false)
        assertTrue(existingGroup("08:00") != null)

        Store.save(c, emptyList())
        AlarmScheduler.scheduleAll(c, emptyList(), observeProgramChanges = false)

        assertNull(existingGroup("08:00"))
        val scheduled = c.getSharedPreferences("dosefolk_alarm_scheduler", Context.MODE_PRIVATE)
            .getStringSet("scheduled_times", emptySet())
            .orEmpty()
        assertTrue(scheduled.isEmpty())
    }

    @Test
    fun staleBroadcast_isRejectedAfterProgramMoved() {
        val date = LocalDate.now().toString()
        assertTrue(AlarmDeliveryGuard.shouldDeliver(c, "08:00", date, listOf(med.id), isSnooze = false))

        Store.save(c, listOf(med.copy(times = listOf("09:00"))))

        assertFalse(AlarmDeliveryGuard.shouldDeliver(c, "08:00", date, listOf(med.id), isSnooze = false))
        assertTrue(AlarmDeliveryGuard.shouldDeliver(c, "09:00", date, listOf(med.id), isSnooze = false))
    }

    @Test
    fun staleBroadcast_isRejectedAfterMedicationDeleted() {
        val date = LocalDate.now().toString()
        Store.save(c, emptyList())
        assertFalse(AlarmDeliveryGuard.shouldDeliver(c, "08:00", date, listOf(med.id), isSnooze = false))
    }

    @Test
    fun explicitSnooze_isNotSilentlyDroppedByProgramRaceGuard() {
        val date = LocalDate.now().toString()
        Store.save(c, listOf(med.copy(times = listOf("09:00"))))
        assertTrue(AlarmDeliveryGuard.shouldDeliver(c, "08:00", date, listOf(med.id), isSnooze = true))
    }

    @Test
    fun newerRevisionWinsEvenWhenItsWallClockIsOlder() {
        val first = med.copy(name = "Old edit", times = listOf("08:00"))
        val second = med.copy(name = "New edit", times = listOf("09:00"))

        ProgramSync.applyRemote(c, remoteProgramEvent("e5", "device-a", 5L, 900_000L, first))
        ProgramSync.applyRemote(c, remoteProgramEvent("e6", "device-a", 6L, 100_000L, second))

        assertEquals("New edit", OwnerScopeStore.remoteMedications(c, "remote-owner").single().name)
        assertEquals(listOf("09:00"), OwnerScopeStore.remoteMedications(c, "remote-owner").single().times)
    }

    @Test
    fun staleRevisionCannotWinWithFutureSkewedWallClock() {
        val newest = med.copy(name = "Newest", times = listOf("09:00"))
        val stale = med.copy(name = "Stale future clock", times = listOf("10:00"))

        ProgramSync.applyRemote(c, remoteProgramEvent("e9", "device-a", 9L, 100_000L, newest))
        ProgramSync.applyRemote(c, remoteProgramEvent("e8", "device-a", 8L, 9_000_000L, stale))

        assertEquals("Newest", OwnerScopeStore.remoteMedications(c, "remote-owner").single().name)
    }

    @Test
    fun concurrentEqualRevisionsConvergeRegardlessOfArrivalOrder() {
        val fromA = remoteProgramEvent("event-a", "device-a", 12L, 900_000L, med.copy(name = "A"))
        val fromB = remoteProgramEvent("event-b", "device-b", 12L, 100_000L, med.copy(name = "B"))

        ProgramSync.applyRemote(c, fromA)
        ProgramSync.applyRemote(c, fromB)
        val forward = OwnerScopeStore.remoteMedications(c, "remote-owner").single().name

        c.getSharedPreferences("dosefolk_program_sync", Context.MODE_PRIVATE).edit().clear().commit()
        c.getSharedPreferences("dosefolk_owner_scope", Context.MODE_PRIVATE).edit().clear().commit()

        ProgramSync.applyRemote(c, fromB)
        ProgramSync.applyRemote(c, fromA)
        val reverse = OwnerScopeStore.remoteMedications(c, "remote-owner").single().name

        assertEquals(forward, reverse)
        assertEquals("B", reverse)
    }

    @Test
    fun observedRemoteRevisionAdvancesNextLocalRevision() {
        EventStore.observeRevision(c, 42L)
        assertEquals(43L, EventStore.nextRevision(c))
    }
}
