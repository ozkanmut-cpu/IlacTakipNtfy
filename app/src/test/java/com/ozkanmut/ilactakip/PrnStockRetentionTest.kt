package com.ozkanmut.ilactakip

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class PrnStockRetentionTest {
    private lateinit var c: Context
    private val med = Medication("med-prn-retention", "PRN Retention Med", "1 tablet", emptyList())

    @Before
    fun setUp() {
        c = ApplicationProvider.getApplicationContext()
        listOf("ilac_takip", "dosefolk_stock", "dosefolk_owner_scope", "dosefolk_events")
            .forEach { c.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        Store.save(c, listOf(med))
        StockEngine.configure(c, med, packSize = 20, currentDoses = 20, lowThreshold = 2)
    }

    private fun event(id: String, type: String, time: String = "PRN", date: String = LocalDate.now().toString()) = DoseEvent(
        eventId = id,
        type = type,
        time = time,
        actor = "local",
        actorTopic = "",
        timestamp = System.currentTimeMillis(),
        medications = listOf(med),
        syncState = "synced",
        revision = 1L,
        scheduledDate = date,
        ownerId = ""
    )

    @Test
    fun ancientPrnReplay_doesNotConsumeStockAgainAfterProcessedAndLedgerRollover() {
        val oldPrn = event("old-prn", "prn_taken")
        StockEngine.applyEvent(c, oldPrn)
        assertEquals(19, StockEngine.forMedication(c, med.id)?.remainingDoses)

        val stockPrefs = c.getSharedPreferences("dosefolk_stock", Context.MODE_PRIVATE)
        val hugeConsumed = JSONArray().put("prn|old-prn|${med.id}")
        repeat(4_100) { i -> hugeConsumed.put("dose|2025-01-${(i % 28) + 1}|${i % 24}:00|history-$i") }
        val rolledProcessed = JSONArray()
        repeat(2_000) { i -> rolledProcessed.put("newer-event-$i") }
        stockPrefs.edit()
            .putString("consumed_sessions", hugeConsumed.toString())
            .putString("processed_events", rolledProcessed.toString())
            .commit()

        // Any later stock mutation triggers ledger compaction. The PRN identity must survive it.
        val todayDose = event("today-dose", "taken", time = "08:00")
        StockEngine.applyEvent(c, todayDose)
        assertEquals(18, StockEngine.forMedication(c, med.id)?.remainingDoses)

        // processed_events no longer knows old-prn, so the PRN ledger is the last idempotency barrier.
        StockEngine.applyEvent(c, oldPrn)
        assertEquals(18, StockEngine.forMedication(c, med.id)?.remainingDoses)
    }
}
