package com.ozkanmut.ilactakip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SgkParserToleranceTest {
    @Test
    fun parsesSpacedPrescriptionCodeAndMixedDateSeparators() {
        val text = """
            Reçete No Reçete Tarihi Sağlık Tesisi Doktor Eczane Adı
            A B12 34C
            1/9/2026
            VASOXEN 5 MG 28 FILM TABLET
            2 Adet
            1 x 1
            1 Günde
            02-09-2026
            30 / 09 / 2026
            Hipertansiyon
        """.trimIndent()

        val rows = SgkMedicationParser.parse(text)

        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals("AB1234C", row.prescriptionNo)
        assertEquals("01.09.2026", row.prescriptionDate)
        assertEquals("02.09.2026", row.fillDate)
        assertEquals("30.09.2026", row.doseEndDate)
        assertEquals("1x1", row.dosePattern)
        assertEquals(2, row.boxCount)
        assertTrue(MedicationIdentity.same("Vasoxen", row.medicationName))
    }

    @Test
    fun unicodeMultiplyAndHeadersDoNotCreateFakeRows() {
        val text = """
            REÇETE NO
            İLAÇ ADI
            C9D8E7F 03.09.2026
            EUTHYROX 50 MCG 100 TABLET
            1 Adet
            1 × 1
            1 Günde
            03.09.2026
            01.12.2026
            Hipotiroidi
        """.trimIndent()

        val rows = SgkMedicationParser.parse(text)

        assertEquals(1, rows.size)
        assertEquals("C9D8E7F", rows.single().prescriptionNo)
        assertEquals("1x1", rows.single().dosePattern)
        assertTrue(MedicationIdentity.same("Euthyrox", rows.single().medicationName))
    }

    @Test
    fun duplicateOcrRowsCollapseByCanonicalMedicationIdentity() {
        val text = """
            A1B2C3D 01.09.2026
            VASOXEN 5 MG 28 FILM TABLET
            1 Adet
            1 x 1
            01.09.2026
            28.09.2026
            Hipertansiyon
            A 1 B 2 C 3 D 01/09/2026
            Vasoxen 5 mg 28 tablet
            1 Adet
            1 x 1
            01-09-2026
            28-09-2026
            Hipertansiyon
        """.trimIndent()

        val rows = SgkMedicationParser.parse(text)
        assertEquals(1, rows.size)
    }
}
