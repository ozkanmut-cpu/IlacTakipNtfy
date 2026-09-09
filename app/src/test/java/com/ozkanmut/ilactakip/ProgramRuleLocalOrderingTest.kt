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

    @Test
    fun staleRemoteRuleCannotOverwriteNewerLocalRuleEvenWithFutureWallClock() {
        val local = ProgramRule(med.id, weekdays = setOf(1, 3, 5), routineLabel = "local")
        ProgramRuleStore.save(c, local)

        val emitted = EventStore.load(c).firstOrNull {
            it.type == "program_rule_updated" && it.actorTopic == Store.topic(c) && it.medications.firstOrNull()?.id == med.id
        }
        assertTrue(emitted != null)

        val stale = ProgramRule(med.id, weekdays = setOf(2, 4), routineLabel = "stale-remote")
        val carrier = Medication(med.id, med.name, ProgramRuleStore.encode(stale).toString(), emptyList())
        val staleRemote = DoseEvent(
            eventId = "stale-remote-rule",
            type = "program_rule_updated",
            time = "program",
            actor = "remote-device",
            actorTopic = "remote-device",
            timestamp = System.currentTimeMillis() + 7L * 24L * 60L * 60L * 1000L,
            medications = listOf(carrier),
            syncState = "synced",
            revision = (emitted!!.revision - 1L).coerceAtLeast(0L),
            ownerId = OwnerScopeStore.localOwnerId(c)
        )

        ProgramRuleStore.applyRemote(c, staleRemote)

        assertEquals(local, ProgramRuleStore.get(c, med.id))
    }
}
