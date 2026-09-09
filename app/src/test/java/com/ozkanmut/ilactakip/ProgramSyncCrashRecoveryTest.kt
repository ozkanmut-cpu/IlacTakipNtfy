package com.ozkanmut.ilactakip

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProgramSyncCrashRecoveryTest {
    private lateinit var c: Context
    private val before = Medication("med-program-crash", "Program Med", "1 tablet", listOf("08:00"))
    private val after = before.copy(times = listOf("09:00"))

    @Before
    fun setUp() {
        c = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(c)
        listOf(
            "ilac_takip",
            "dosefolk_events",
            "dosefolk_program_sync",
            "dosefolk_owner_scope",
            "dosefolk_delivery_ledger"
        ).forEach { c.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        Store.save(c, listOf(before))
        ProgramSync.observeLocal(c, listOf(before))
    }

    @Test
    fun persistedProgramEventBeforeBaselineCommit_isReusedWithoutRevisionChurn() {
        val ownerId = OwnerScopeStore.localOwnerId(c)
        val eventId = "program-crash-event"
        val signature = "program_updated|" + fingerprint(before) + "->" + fingerprint(after)
        val p = c.getSharedPreferences("dosefolk_program_sync", Context.MODE_PRIVATE)
        p.edit().putString(
            "pending|$ownerId|${before.id}",
            JSONObject().put("signature", signature).put("eventId", eventId).toString()
        ).commit()

        EventStore.append(c, DoseEvent(
            eventId = eventId,
            type = "program_updated",
            time = "09:00",
            actor = Store.myName(c),
            actorTopic = Store.topic(c),
            timestamp = System.currentTimeMillis(),
            medications = listOf(after),
            syncState = "pending",
            revision = 9L,
            ownerId = ownerId
        ))
        assertEquals(9L, c.getSharedPreferences("dosefolk_events", Context.MODE_PRIVATE).getLong("local_revision", 0L))

        Store.save(c, listOf(after))
        ProgramSync.observeLocal(c, listOf(after))

        assertEquals(1, EventStore.load(c).count { it.eventId == eventId && it.type == "program_updated" })
        assertEquals(1, EventStore.load(c).count { it.type == "program_updated" && it.medications.firstOrNull()?.id == before.id })
        assertEquals(9L, c.getSharedPreferences("dosefolk_events", Context.MODE_PRIVATE).getLong("local_revision", 0L))
        assertFalse(p.contains("pending|$ownerId|${before.id}"))

        ProgramSync.observeLocal(c, listOf(after))
        assertEquals(1, EventStore.load(c).count { it.type == "program_updated" && it.medications.firstOrNull()?.id == before.id })
        assertEquals(9L, c.getSharedPreferences("dosefolk_events", Context.MODE_PRIVATE).getLong("local_revision", 0L))
    }

    private fun fingerprint(med: Medication): String = listOf(
        med.id,
        med.name,
        med.dose,
        med.times.joinToString("\u001f")
    ).joinToString("\u001e")
}
