package com.ozkanmut.ilactakip

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class ReliabilityCoreTest {
    private lateinit var c: Context
    private val med = Medication("med-1", "Test Med", "", listOf("08:00"))
    private val today = LocalDate.now().toString()

    @Before
    fun setUp() {
        c = ApplicationProvider.getApplicationContext()
        listOf(
            "ilac_takip",
            "dosefolk_events",
            "dosefolk_stock",
            "dosefolk_alarm_scheduler",
            "dosefolk_medication_meta",
            "dosefolk_remote_medication_meta",
            "dosefolk_program_rules",
            "dosefolk_owner_scope",
            "dosefolk_low_stock_alerts"
        ).forEach { c.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    private fun event(id: String, type: String, timestamp: Long, meds: List<Medication> = listOf(med)) = DoseEvent(
        eventId = id,
        type = type,
        time = "08:00",
        actor = "Tester",
        actorTopic = Store.topic(c),
        timestamp = timestamp,
        medications = meds,
        syncState = "synced",
        revision = timestamp,
        scheduledDate = today,
        ownerId = Store.topic(c)
    )

    @Test
    fun eventStore_deduplicatesSameEventId() {
        EventStore.append(c, event("same-id", "taken", 100L))
        EventStore.append(c, event("same-id", "missed", 200L))

        val events = EventStore.load(c)
        assertEquals(1, events.size)
        assertEquals("taken", events.single().type)
    }

    @Test
    fun doseState_undoTakenReturnsSessionToPending() {
        Store.save(c, listOf(med))
        EventStore.append(c, event("taken-1", "taken", 100L))
        EventStore.append(c, event("undo-1", "undo_taken", 200L))

        val state = DoseStateEngine.stateForTime(c, "08:00", LocalDate.now())
        assertEquals(DoseSessionStatus.PENDING, state.status)
        assertEquals("undo_taken", state.latestEvent?.type)
    }

    @Test
    fun stock_takenIsIdempotentAndUndoRestoresDose() {
        StockEngine.configure(c, med, packSize = 10, currentDoses = 10, lowThreshold = 2)
        val taken = event("taken-stock", "taken", 100L)

        StockEngine.applyEvent(c, taken)
        StockEngine.applyEvent(c, taken)
        assertEquals(9, StockEngine.forMedication(c, med.id)?.remainingDoses)

        StockEngine.applyEvent(c, event("undo-stock", "undo_taken", 200L))
        assertEquals(10, StockEngine.forMedication(c, med.id)?.remainingDoses)
    }

    @Test
    fun remoteStock_olderSnapshotCannotRollBackNewerState() {
        StockEngine.saveRemoteSnapshot(c, "owner-2", MedicationStock(med.id, med.name, 6, 10, 2, updatedAt = 200L))
        StockEngine.saveRemoteSnapshot(c, "owner-2", MedicationStock(med.id, med.name, 9, 10, 2, updatedAt = 100L))

        val stock = StockEngine.remoteForMedication(c, "owner-2", med.id)
        assertNotNull(stock)
        assertEquals(6, stock?.remainingDoses)
        assertEquals(200L, stock?.updatedAt)
    }

    @Test
    fun alarmPlan_canBeRebuiltFromPersistedMedicationAfterSchedulerStateLoss() {
        Store.save(c, listOf(med))
        c.getSharedPreferences("dosefolk_alarm_scheduler", Context.MODE_PRIVATE).edit().clear().commit()

        AlarmScheduler.scheduleAll(c, Store.load(c), observeProgramChanges = false)

        val scheduled = c.getSharedPreferences("dosefolk_alarm_scheduler", Context.MODE_PRIVATE)
            .getStringSet("scheduled_times", emptySet()).orEmpty()
        assertTrue("08:00" in scheduled)
    }
}
