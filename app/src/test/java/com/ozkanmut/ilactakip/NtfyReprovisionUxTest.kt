package com.ozkanmut.ilactakip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class NtfyReprovisionUxTest {
    @Test
    fun `normal state does not show reprovision banner`() {
        assertNull(NtfyReprovisionUx.model(reprovisionRequired = false))
    }

    @Test
    fun `reprovision state exposes human readable recovery banner and cta`() {
        val model = NtfyReprovisionUx.model(reprovisionRequired = true)
        assertNotNull(model)
        requireNotNull(model)

        assertEquals("Bildirim bağlantısını yenile", model.titleTr)
        assertEquals(
            "Güvenli bildirim bağlantısı artık geçerli değil. Size gönderilen yeni bağlantı linkini bu telefonda açın.",
            model.bodyTr
        )
        assertEquals("Nasıl yenilerim?", model.actionTr)
        assertEquals("Refresh notification connection", model.titleEn)
        assertEquals(
            "The secure notification connection is no longer valid. Open the new connection link sent to you on this phone.",
            model.bodyEn
        )
        assertEquals("How do I refresh it?", model.actionEn)
    }
}
