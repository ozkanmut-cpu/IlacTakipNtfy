package com.ozkanmut.ilactakip

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProgramRuleLocalOrderingTest {
    private lateinit var c: Context
    private val med = Medication("rule-local-med", "Rule Local Med", "1 tablet", listOf("08:00"))

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

    private fun staleRemoteFor(localEvent: DoseEvent): DoseEvent {
        val stale = ProgramRule(med.id, weekdays = setOf(2, 4), routineLabel = "stale-remote")
        val carrier = Medication(med.id, med.name, ProgramRuleStore.encode(stale).toString(), emptyList())
        return DoseEvent(
            eventId = "stale-remote-rule",
            type = "program_rule_updated",
            time = "program",
            actor = "remote-device",
            actorTopic = "remote-device",
            timestamp = System.currentTimeMillis() + 7L * 24L * 60L * 60L * 1000L,
            medications = listOf(carrier),
            syncState = "synced",
            revision = (localEvent.revision - 1L).coerceAtLeast(0L),
            ownerId = OwnerScopeStore.localOwnerId(c)
        )
    }

    @Test
    fun staleRemoteRuleCannotOverwriteNewerLocalRuleEvenWithFutureWallClock() {
        val local = ProgramRule(med.id, weekdays = setOf(1, 3, 5), routineLabel = "local")
        ProgramRuleStore.save(c, local)

        val emitted = EventStore.load(c).firstOrNull {
            it.type == "program_rule_updated" && it.actorTopic == Store.topic(c) && it.medications.firstOrNull()?.id == med.id
        }
        assertTrue(emitted != null)

        ProgramRuleStore.applyRemote(c, staleRemoteFor(emitted!!))

        assertEquals(local, ProgramRuleStore.get(c, med.id))
    }

    @Test
    fun crashAfterLocalEventAppend_recoversOrderingBeforeRemoteRuleIsJudged() {
        val local = ProgramRule(med.id, weekdays = setOf(1, 3, 5), routineLabel = "local-after-crash")
        ProgramRuleStore.save(c, local)

        val emitted = EventStore.load(c).firstOrNull {
            it.type == "program_rule_updated" && it.actorTopic == Store.topic(c) && it.medications.firstOrNull()?.id == med.id
        }
        assertTrue(emitted != null)

        // Simulate a process death after EventStore append but before rule ordering
        // metadata became durable. Keep the rule and event, erase only ordering keys.
        val p = c.getSharedPreferences("dosefolk_program_rules", Context.MODE_PRIVATE)
        p.edit()
            .remove("stamp|${med.id}")
            .remove("rev|${med.id}")
            .remove("actor|${med.id}")
            .remove("event|${med.id}")
            .commit()

        ProgramRuleStore.applyRemote(c, staleRemoteFor(emitted!!))

        assertEquals(local, ProgramRuleStore.get(c, med.id))
        assertEquals(emitted.revision, p.getLong("rev|${med.id}", 0L))
        assertEquals(emitted.eventId, p.getString("event|${med.id}", ""))
    }
}
