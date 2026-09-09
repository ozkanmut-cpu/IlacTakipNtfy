package com.ozkanmut.ilactakip

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProgramRuleCrashIdempotencyTest {
    private lateinit var c: Context
    private val med = Medication("rule-crash-med", "Rule Crash Med", "1 tablet", listOf("08:00"))

    @Before
    fun setUp() {
        c = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(c)
        listOf(
            "ilac_takip",
            "dosefolk_events",
            "dosefolk_program_rules",
            "dosefolk_alarm_scheduler",
            "dosefolk_owner_scope",
            "dosefolk_delivery_ledger"
        ).forEach { c.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        Store.save(c, listOf(med))
    }

    @Test
    fun persistedRuleWithPendingOperation_recoversExactEventWithoutRevisionChurn() {
        val rule = ProgramRule(med.id, weekdays = setOf(1, 3, 5), routineLabel = "crash-safe")
        val signature = ProgramRuleStore.encode(rule).toString()
        val eventId = "pending-rule-event"

        // Simulate process death after the pending id and rule body were committed,
        // but before the sync event/checkpoint was durably completed.
        c.getSharedPreferences("dosefolk_program_rules", Context.MODE_PRIVATE).edit()
            .putString("rules", "[${ProgramRuleStore.encode(rule)}]")
            .putString("pending|${med.id}", org.json.JSONObject()
                .put("signature", signature)
                .put("eventId", eventId)
                .toString())
            .commit()

        val revisionBefore = c.getSharedPreferences("dosefolk_events", Context.MODE_PRIVATE)
            .getLong("local_revision", 0L)

        ProgramRuleStore.save(c, rule)

        val emitted = EventStore.load(c).firstOrNull { it.eventId == eventId }
        assertNotNull(emitted)
        assertEquals("program_rule_updated", emitted!!.type)
        assertEquals(rule, ProgramRuleStore.get(c, med.id))
        assertEquals(emitted.revision, c.getSharedPreferences("dosefolk_program_rules", Context.MODE_PRIVATE)
            .getLong("rev|${med.id}", 0L))
        assertFalse(c.getSharedPreferences("dosefolk_program_rules", Context.MODE_PRIVATE)
            .contains("pending|${med.id}"))
        assertTrue(emitted.revision > revisionBefore)

        val revisionAfterRecovery = c.getSharedPreferences("dosefolk_events", Context.MODE_PRIVATE)
            .getLong("local_revision", 0L)
        ProgramRuleStore.save(c, rule)

        assertEquals(1, EventStore.load(c).count { it.eventId == eventId })
        assertEquals(revisionAfterRecovery, c.getSharedPreferences("dosefolk_events", Context.MODE_PRIVATE)
            .getLong("local_revision", 0L))
    }
}
