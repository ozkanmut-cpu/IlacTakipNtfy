package com.ozkanmut.ilactakip

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
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
class SnoozeAcceptanceRaceTest {
    private lateinit var c: Context
    private val med = Medication("race-med", "Race Med", "1 tablet", listOf("08:00"))
    private val date get() = LocalDate.now().toString()

    @Before
    fun setUp() {
        c = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(c)
        listOf(
            "ilac_takip",
            "dosefolk_events",
            "dosefolk_alarm_scheduler",
            "dosefolk_attention_budget",
            "dosefolk_care_baton",
            "dosefolk_owner_scope",
            "dosefolk_delivery_ledger"
        ).forEach { c.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        Store.save(c, listOf(med))
    }

    private fun snoozePendingIntent(): PendingIntent? = PendingIntent.getBroadcast(
        c,
        AlarmScheduler.snoozeKey("08:00", date).hashCode(),
        Intent(c, AlarmReceiver::class.java),
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
    )

    @Test
    fun rejectedSnooze_doesNotLeavePhysicalSnoozeAlarm() {
        EventStore.append(c, DoseEvent(
            eventId = "remote-terminal",
            type = "taken",
            time = "08:00",
            actor = "Remote",
            actorTopic = "remote-device",
            timestamp = System.currentTimeMillis(),
            medications = listOf(med),
            syncState = "synced",
            revision = 2L,
            scheduledDate = date,
            ownerId = Store.topic(c)
        ))

        val accepted = Ntfy.sendEvent(
            c,
            "snoozed",
            "08:00",
            listOf(med),
            date,
            System.currentTimeMillis() + 30 * 60_000L
        )

        assertFalse(accepted)
        assertNull(snoozePendingIntent())
        assertFalse(EventStore.load(c).any { it.type == "snoozed" && it.time == "08:00" && it.scheduledDate == date })
    }

    @Test
    fun acceptedSnooze_persistsEventBeforeSchedulingAlarm() {
        val deadline = System.currentTimeMillis() + 30 * 60_000L

        val accepted = Ntfy.sendEvent(c, "snoozed", "08:00", listOf(med), date, deadline)

        assertTrue(accepted)
        assertTrue(EventStore.load(c).any {
            it.type == "snoozed" && it.time == "08:00" && it.scheduledDate == date && it.snoozeUntil == deadline
        })
        assertNotNull(snoozePendingIntent())
    }
}
