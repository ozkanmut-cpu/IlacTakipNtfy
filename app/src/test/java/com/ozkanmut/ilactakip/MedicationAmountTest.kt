package com.ozkanmut.ilactakip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MedicationAmountTest {
    @Test fun presetAndCustomTabletChoicesProduceTheSavedQuantity() {
        val dose = MedicationAmount.newTablet()
        assertEquals(1.0, dose.quantity)
        assertEquals(0.25, dose.select(TabletChoice.QUARTER).quantity)
        assertEquals(0.5, dose.select(TabletChoice.HALF).quantity)
        val custom = dose.select(TabletChoice.CUSTOM)
        assertNull(custom.quantity)
        assertEquals(1.25, custom.copy(text = "1,25").quantity)
        for (text in listOf("", "0", "-1", "1,2.3", "NaN", "1e4")) {
            assertNull(custom.copy(text = text).quantity)
        }
    }

    @Test fun openingAnExistingUnknownAmountDoesNotAssignOneTablet() {
        assertNull(MedicationAmount.existing(null).quantity)
        assertEquals(TabletChoice.CUSTOM, MedicationAmount.existing(null).choice)
        assertEquals(TabletChoice.QUARTER, MedicationAmount.existing(0.25).choice)
        assertEquals(1.5, MedicationAmount.existing(1.5).quantity)
    }
}
