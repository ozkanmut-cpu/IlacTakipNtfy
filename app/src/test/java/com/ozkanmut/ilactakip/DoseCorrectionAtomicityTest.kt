package com.ozkanmut.ilactakip

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
class DoseCorrectionAtomicityTest {
    private lateinit var c: Context
    private val med = Medication("med-correction", "Correction Med", "1 tablet", listOf("08:00"))
    private val today = LocalDate.now().toString()

    @Before
    fun setUp() {
        c = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(c)
        listOf(
            "ilac_takip",
            "dosefolk_events",
            "dosefolk_program_rules",
            "dosefolk_owner_scope",
            "dosefolk_stock",
            "dosefolk_delivery_ledger",
            "dosefolk_alarm_scheduler",
            "dosefolk_attention_budget",
            "dosefolk_care_baton"
        ).forEach { c.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        Store.save(c, listOf(med))
    }

    private fun terminal(id: String, type: String, revision: Long) = DoseEvent(
        eventId = id,
        type = type,
        time = "08:00",
        actor = "Local",
        actorTopic = Store.topic(c),
        timestamp = System.currentTimeMillis(),
        medications = listOf(med),
        syncState = "synced",
        revision = revision,
        scheduledDate = today,
        ownerId = OwnerScopeStore.localOwnerId(c)
    )

    @Test
    fun missedToTakenCorrection_emitsOneResolutionWithoutIntermediateUndo() {
        EventStore.append(c, terminal("missed-original", "missed", 1L))

        assertTrue(DoseCorrectionEngine.correctToTaken(c, "08:00", today))

        val events = EventStore.load(c)
        assertFalse(events.any { it.type == "undo_missed" })
        assertEquals(1, events.count { it.type == "conflict_resolved_taken" })
        assertEquals(DoseSessionStatus.TAKEN, DoseStateEngine.stateForTime(c, "08:00").status)
    }

    @Test
    fun takenToMissedCorrection_emitsOneResolutionWithoutIntermediateUndo() {
        EventStore.append(c, terminal("taken-original", "taken", 1L))

        assertTrue(DoseCorrectionEngine.correctToMissed(c, "08:00", today))

        val events = EventStore.load(c)
        assertFalse(events.any { it.type == "undo_taken" })
        assertEquals(1, events.count { it.type == "conflict_resolved_missed" })
        assertEquals(DoseSessionStatus.MISSED, DoseStateEngine.stateForTime(c, "08:00").status)
    }
}
