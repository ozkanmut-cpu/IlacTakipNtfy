package com.ozkanmut.ilactakip

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RemoteProgramAtomicityTest {
    private lateinit var c: Context
    private val owner = "remote-owner"
    private val med = Medication("remote-med", "Remote Med", "1 tablet", listOf("08:00"))

    @Before
    fun setUp() {
        c = ApplicationProvider.getApplicationContext()
        c.getSharedPreferences("dosefolk_owner_scope", Context.MODE_PRIVATE).edit().clear().commit()
        c.getSharedPreferences("dosefolk_store", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun deleteCommitsMedicationRuleAndOwnerStateAsOneConvergentTransition() {
        OwnerScopeStore.applyRemoteProgram(c, owner, "program_added", med)
        assertEquals(listOf(med), OwnerScopeStore.remoteMedications(c, owner))
        assertEquals(owner, OwnerScopeStore.ownerOf(c, med.id))

        val rule = ProgramRule(medicationId = med.id, weekdays = setOf(1, 3, 5))
        val ruleEvent = DoseEvent(
            eventId = "remote-rule-1",
            type = "program_rule_updated",
            time = "program",
            actor = "Remote",
            actorTopic = owner,
            timestamp = 100L,
            medications = listOf(med),
            syncState = "synced",
            revision = 1L,
            ownerId = owner
        )
        assertTrue(OwnerScopeStore.applyRemoteRule(c, owner, rule, ruleEvent))
        assertEquals(setOf(1, 3, 5), OwnerScopeStore.remoteRule(c, owner, med.id).weekdays)

        OwnerScopeStore.applyRemoteProgram(c, owner, "program_deleted", med)

        assertTrue(OwnerScopeStore.remoteMedications(c, owner).isEmpty())
        assertEquals(emptySet<Int>(), OwnerScopeStore.remoteRule(c, owner, med.id).weekdays)
        assertEquals(owner, OwnerScopeStore.ownerOf(c, med.id))

        // Simulate replay after ProgramSync side effect completed but its ordering
        // checkpoint did not. Reapplying the same delete must remain convergent.
        OwnerScopeStore.applyRemoteProgram(c, owner, "program_deleted", med)
        assertTrue(OwnerScopeStore.remoteMedications(c, owner).isEmpty())
        assertEquals(emptySet<Int>(), OwnerScopeStore.remoteRule(c, owner, med.id).weekdays)
    }
}
