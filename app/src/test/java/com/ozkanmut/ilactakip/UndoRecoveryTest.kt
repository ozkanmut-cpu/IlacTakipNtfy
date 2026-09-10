package com.ozkanmut.ilactakip

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class UndoRecoveryTest {
    private lateinit var c: Context
    private val med = Medication("undo-med", "Undo Med", "1 tablet", listOf("08:00"))

    @Before
    fun setUp() {
        c = ApplicationProvider.getApplicationContext()
        listOf("ilac_takip", "dosefolk_events", "dosefolk_alarm_scheduler", "dosefolk_undo_recovery")
            .forEach { c.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        Store.save(c, listOf(med))
    }

    private fun event(id: String, type: String, date: LocalDate) = DoseEvent(
        eventId = id,
        type = type,
        time = "08:00",
        actor = "Tester",
        actorTopic = Store.topic(c),
        timestamp = System.currentTimeMillis(),
        medications = listOf(med),
        syncState = "synced",
        revision = System.currentTimeMillis(),
        scheduledDate = date.toString(),
        ownerId = Store.topic(c)
    )

    @Test
    fun undoToday_rearmsOnlyOnceAsRegularPendingAlarm() {
        val today = LocalDate.now()
        EventStore.append(c, event("taken", "taken", today))
        val undo = event("undo", "undo_taken", today)
        EventStore.append(c, undo)

        assertTrue(UndoRecovery.recoverEvent(c, undo))
        val regularPi = PendingIntent.getBroadcast(
            c,
            AlarmScheduler.pendingRearmKey("08:00", today.toString()).hashCode(),
            Intent(c, AlarmReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        val snoozePi = PendingIntent.getBroadcast(
            c,
            AlarmScheduler.snoozeKey("08:00", today.toString()).hashCode(),
            Intent(c, AlarmReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        assertNotNull(regularPi)
        assertNull(snoozePi)
        assertTrue(AlarmDeliveryGuard.shouldDeliver(c, "08:00", today.toString(), listOf(med.id), false))
        assertFalse(UndoRecovery.recoverEvent(c, undo))
    }

    @Test
    fun terminalResolution_cancelsPendingRearm() {
        val today = LocalDate.now()
        EventStore.append(c, event("taken", "taken", today))
        val undo = event("undo", "undo_taken", today)
        EventStore.append(c, undo)
        assertTrue(UndoRecovery.recoverEvent(c, undo))

        assertTrue(Ntfy.sendEvent(c, "taken", "08:00", listOf(med), today.toString(), eventId = "taken-after-undo"))
        val regularPi = PendingIntent.getBroadcast(
            c,
            AlarmScheduler.pendingRearmKey("08:00", today.toString()).hashCode(),
            Intent(c, AlarmReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        assertNull(regularPi)
        assertFalse(AlarmDeliveryGuard.shouldDeliver(c, "08:00", today.toString(), listOf(med.id), false))
    }

    @Test
    fun historicalUndo_doesNotCreateAlarm() {
        val yesterday = LocalDate.now().minusDays(1)
        EventStore.append(c, event("taken-old", "taken", yesterday))
        val undo = event("undo-old", "undo_taken", yesterday)
        EventStore.append(c, undo)

        assertFalse(UndoRecovery.recoverEvent(c, undo))
    }
}
