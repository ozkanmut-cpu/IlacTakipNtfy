package com.ozkanmut.ilactakip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SgkImportSafetyTest {
    @Test
    fun extractsExplicitTabletPackageCount() {
        assertEquals(28, SgkStockAutoImporter.explicitPackageCount("VASOXEN 5 MG 28 FILM TABLET"))
        assertEquals(100, SgkStockAutoImporter.explicitPackageCount("EUTHYROX 50 MCG 100 TABLET"))
    }

    @Test
    fun extractsExplicitCapsuleAndNebuleCounts() {
        assertEquals(30, SgkStockAutoImporter.explicitPackageCount("TEOKAP SR 200 MG 30 KAPSUL"))
        assertEquals(20, SgkStockAutoImporter.explicitPackageCount("VENTOFOR 2.5 MG 20 NEBUL"))
    }

    @Test
    fun rejectsAmbiguousOrMissingPackageCounts() {
        assertNull(SgkStockAutoImporter.explicitPackageCount("VASOXEN 5 MG"))
        assertNull(SgkStockAutoImporter.explicitPackageCount("TEST 10 TABLET + 20 TABLET"))
    }
}
