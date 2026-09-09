package com.ozkanmut.ilactakip

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class SyncCheckpointRecoveryTest {
    private lateinit var c: Context
    private val med = Medication("med-checkpoint", "Checkpoint Med", "1 tablet", listOf("08:00"))

    @Before
    fun setUp() {
        c = ApplicationProvider.getApplicationContext()
        listOf(
            "dosefolk_sync",
            "dosefolk_events",
            "dosefolk_remote_event_receipts",
            "ilac_takip"
        ).forEach { c.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        Store.save(c, listOf(med))
    }

    private fun remoteEvent(id: String) = DoseEvent(
        eventId = id,
        type = "taken",
        time = "08:00",
        actor = "Remote",
        actorTopic = "remote-phone",
        timestamp = 100_000L,
        medications = listOf(med),
        syncState = "synced",
        revision = 1L,
        scheduledDate = LocalDate.now().toString(),
        ownerId = Store.topic(c)
    )

    @Test
    fun cursorStartsAtSafeCatchupWindowUntilBatchCommits() {
        assertEquals("24h", SyncCheckpointStore.since(c))
        assertEquals(0L, SyncCheckpointStore.lastSuccess(c))

        // Simulates a crash/failure: no successful-batch commit is made.
        EventStore.append(c, remoteEvent("remote-1"))

        assertEquals("24h", SyncCheckpointStore.since(c))
        assertEquals(0L, SyncCheckpointStore.lastSuccess(c))
        assertTrue(EventStore.contains(c, "remote-1"))
    }

    @Test
    fun successfulBatchAdvancesCursorAndSuccessTimestampTogether() {
        SyncCheckpointStore.commitSuccessfulBatch(c, "ntfy-123", 42_000L)

        assertEquals("ntfy-123", SyncCheckpointStore.since(c))
        assertEquals(42_000L, SyncCheckpointStore.lastSuccess(c))
    }

    @Test
    fun emptySuccessfulBatchUpdatesHealthButDoesNotDestroyExistingCursor() {
        SyncCheckpointStore.commitSuccessfulBatch(c, "ntfy-123", 42_000L)
        SyncCheckpointStore.commitSuccessfulBatch(c, null, 84_000L)

        assertEquals("ntfy-123", SyncCheckpointStore.since(c))
        assertEquals(84_000L, SyncCheckpointStore.lastSuccess(c))
    }

    @Test
    fun replayAfterCrashDoesNotDuplicatePersistedEvent() {
        val event = remoteEvent("remote-replay")
        EventStore.append(c, event)
        EventStore.append(c, event)

        assertEquals(1, EventStore.load(c).count { it.eventId == "remote-replay" })
        assertFalse(RemoteEventReceiptStore.processed(c, "remote-replay"))

        RemoteEventReceiptStore.markProcessed(c, "remote-replay")
        assertTrue(RemoteEventReceiptStore.processed(c, "remote-replay"))
    }
}
