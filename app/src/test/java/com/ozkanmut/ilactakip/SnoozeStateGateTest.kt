package com.ozkanmut.ilactakip

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class SnoozeStateGateTest {
    private lateinit var c: Context
    private val date get() = LocalDate.now().toString()
    private val med = Medication("med-snooze-gate", "Gate Med", "1 tablet", listOf("08:00"))

    @Before
    fun setUp() {
        c = ApplicationProvider.getApplicationContext()
        listOf("ilac_takip", "dosefolk_events", "dosefolk_alarm_scheduler").forEach {
            c.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        Store.save(c, listOf(med))
    }

    private fun event(id: String, type: String, revision: Long, actorTopic: String, snoozeUntil: Long = 0L) = DoseEvent(
        eventId = id,
        type = type,
        time = "08:00",
        actor = actorTopic,
        actorTopic = actorTopic,
        timestamp = System.currentTimeMillis(),
        medications = listOf(med),
        syncState = "synced",
        revision = revision,
        scheduledDate = date,
        snoozeUntil = snoozeUntil,
        ownerId = Store.topic(c)
    )

    private fun scheduledSnoozeIntent(): PendingIntent? = PendingIntent.getBroadcast(
        c,
        AlarmScheduler.snoozeKey("08:00", date).hashCode(),
        Intent(c, AlarmReceiver::class.java),
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
    )

    @Test
    fun staleRemoteSnooze_afterTerminalState_doesNotCreateAlarm() {
        val deadline = System.currentTimeMillis() + 30 * 60_000L
        EventStore.append(c, event("local-taken", "taken", 3L, "local"))
        val staleSnooze = event("remote-snooze", "snoozed", 2L, "remote", deadline)
        EventStore.append(c, staleSnooze)

        SyncEngine.applyRemoteState(c, staleSnooze)

        assertFalse(DoseStateEngine.stateForTime(c, "08:00", LocalDate.now()).status == DoseSessionStatus.SNOOZED)
        assertNull(scheduledSnoozeIntent())
    }

    @Test
    fun activeRemoteSnooze_createsAlarm() {
        val deadline = System.currentTimeMillis() + 30 * 60_000L
        val snooze = event("remote-snooze-active", "snoozed", 2L, "remote", deadline)
        EventStore.append(c, snooze)

        SyncEngine.applyRemoteState(c, snooze)

        assertTrue(DoseStateEngine.stateForTime(c, "08:00", LocalDate.now()).status == DoseSessionStatus.SNOOZED)
        assertTrue(scheduledSnoozeIntent() != null)
    }
}
