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

@RunWith(RobolectricTestRunner::class)
class NaturalActionSafetyTest {
    private lateinit var c: Context
    private val med = Medication("med-1", "Vasoxen", "5 mg", listOf("08:00"))

    @Before
    fun setUp() {
        c = ApplicationProvider.getApplicationContext()
        listOf(
            "ilac_takip",
            "dosefolk_events",
            "dosefolk_stock",
            "dosefolk_program_rules",
            "dosefolk_alarm_scheduler",
            "dosefolk_owner_scope"
        ).forEach { c.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        Store.save(c, listOf(med))
    }

    @Test
    fun scheduleChange_requiresExplicitConfirmationBeforeMutation() {
        val prompt = NaturalActionRouter.handle(c, "Vasoxen saatini 21:30 olarak değiştir")
        assertNotNull(prompt)
        assertEquals(listOf("08:00"), Store.load(c).single().times)

        val result = NaturalActionRouter.handle(c, "evet")
        assertNotNull(result)
        assertEquals(listOf("21:30"), Store.load(c).single().times)
    }

    @Test
    fun scheduleChange_canBeCancelledWithoutMutation() {
        NaturalActionRouter.handle(c, "Vasoxen saatini 22:00 olarak değiştir")
        val result = NaturalActionRouter.handle(c, "iptal")

        assertNotNull(result)
        assertEquals(listOf("08:00"), Store.load(c).single().times)
    }

    @Test
    fun unknownFreeText_doesNotMutateMedicationProgram() {
        val result = NaturalActionRouter.handle(c, "Bugün biraz yorgunum ve erken yatacağım")

        assertEquals(null, result)
        assertEquals(listOf("08:00"), Store.load(c).single().times)
    }

    @Test
    fun stockQuery_isReadOnly() {
        StockEngine.configure(c, med, packSize = 28, currentDoses = 10, lowThreshold = 5)
        val before = StockEngine.forMedication(c, med.id)?.remainingDoses
        val result = NaturalActionRouter.handle(c, "Vasoxen stok kaç kaldı")
        val after = StockEngine.forMedication(c, med.id)?.remainingDoses

        assertNotNull(result)
        assertTrue(result!!.contains("Vasoxen"))
        assertEquals(before, after)
    }
}
