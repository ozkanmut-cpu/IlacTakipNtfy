package com.ozkanmut.ilactakip

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CirclePairingCodecExtractionTest {
    @Test
    fun pairingUiDelegatesToSharedCodec() {
        val codecClass = runCatching {
            Class.forName("com.ozkanmut.ilactakip.CirclePairingPayload")
        }.getOrNull()
        assertNotNull("shared CirclePairingPayload codec must exist", codecClass)

        val source = File("src/main/java/com/ozkanmut/ilactakip/PairingUi.kt").readText()
        assertTrue(source.contains("CirclePairingPayload.encode("))
        assertTrue(source.contains("CirclePairingPayload.parse("))
        assertFalse(source.contains("private fun parsePairPayload"))
    }
}
