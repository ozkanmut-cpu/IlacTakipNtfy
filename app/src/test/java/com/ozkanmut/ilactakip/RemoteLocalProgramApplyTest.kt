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
class RemoteLocalProgramApplyTest {
    private lateinit var c: Context
    private lateinit var localTopic: String

    @Before
    fun setUp() {
        c = ApplicationProvider.getApplicationContext()
        listOf(
            "ilac_takip",
            "dosefolk_program_sync",
            "dosefolk_owner_scope",
            "dosefolk_alarm_scheduler"
        ).forEach { c.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        localTopic = Store.topic(c)
        Store.save(c, listOf(Medication("med-1", "Old", "1", listOf("08:00"))))
    }

    private fun event(
        type: String,
        med: Medication,
        revision: Long,
        eventId: String
    ) = DoseEvent(
        eventId = eventId,
        type = type,
        time = med.times.firstOrNull() ?: "program",
        actor = "Caregiver",
        actorTopic = "peer-a",
        timestamp = 1_000L + revision,
        medications = listOf(med),
        syncState = "synced",
        revision = revision,
        ownerId = localTopic,
        targetTopic = localTopic
    )

    @Test
    fun authorizedRemoteUpdate_changesRealLocalProgram() {
        val updated = Medication("med-1", "New", "2", listOf("09:30"))
        ProgramSync.applyRemote(c, event("program_updated", updated, 10L, "evt-10"))

        assertEquals(listOf(updated), Store.load(c))
    }

    @Test
    fun replayOfSameRemoteUpdate_isIdempotent() {
        val updated = Medication("med-1", "New", "2", listOf("09:30"))
        val e = event("program_updated", updated, 10L, "evt-10")
        ProgramSync.applyRemote(c, e)
        ProgramSync.applyRemote(c, e)

        assertEquals(1, Store.load(c).size)
        assertEquals(updated, Store.load(c).single())
    }

    @Test
    fun olderRemoteUpdate_cannotOverwriteNewerLocalOwnerState() {
        val newer = Medication("med-1", "Newer", "3", listOf("10:00"))
        val older = Medication("med-1", "Older", "1", listOf("07:00"))
        ProgramSync.applyRemote(c, event("program_updated", newer, 20L, "evt-20"))
        ProgramSync.applyRemote(c, event("program_updated", older, 10L, "evt-10"))

        assertEquals(newer, Store.load(c).single())
    }

    @Test
    fun remoteOwnerProgram_staysInRemoteCache() {
        val remote = Medication("remote-med", "Remote", "1", listOf("12:00"))
        val event = DoseEvent(
            eventId = "remote-1",
            type = "program_added",
            time = "12:00",
            actor = "Peer B",
            actorTopic = "peer-b",
            timestamp = 2_000L,
            medications = listOf(remote),
            syncState = "synced",
            revision = 5L,
            ownerId = "peer-b"
        )

        ProgramSync.applyRemote(c, event)

        assertEquals("Old", Store.load(c).single().name)
        assertTrue(OwnerScopeStore.remoteMedications(c, "peer-b").contains(remote))
    }
}
