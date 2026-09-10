package com.ozkanmut.ilactakip

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DosefolkQaLogTest {
    private lateinit var c: Context

    @Before
    fun setUp() {
        c = ApplicationProvider.getApplicationContext()
        DosefolkQaLog.clear(c)
    }

    @Test
    fun topicMask_isStableAndDoesNotRevealOriginal() {
        val raw = "dosefolk-secret-topic-123"
        val a = DosefolkQaLog.mask(raw)
        val b = DosefolkQaLog.mask(raw)
        assertTrue(a.startsWith("#"))
        assertTrue(a.length == 9)
        assertTrue(a == b)
        assertFalse(a.contains(raw))
        assertNotEquals(DosefolkQaLog.mask("another-topic"), a)
    }

    @Test
    fun record_masksSensitiveFieldsAndKeepsUsefulMetadata() {
        val rawTopic = "dosefolk-private-topic"
        DosefolkQaLog.record(
            c,
            DosefolkQaLog.Category.NTFY_RX,
            "message",
            mapOf("topic" to rawTopic, "status" to 200, "eventType" to "taken")
        )
        val text = DosefolkQaLog.exportFile(c).readText()
        assertTrue(text.contains("\"category\":\"NTFY_RX\""))
        assertTrue(text.contains("\"event\":\"message\""))
        assertTrue(text.contains("\"status\":200"))
        assertTrue(text.contains("\"eventType\":\"taken\""))
        assertFalse(text.contains(rawTopic))
    }

    @Test
    fun record_includesStableSessionAndBuildDeviceMetadata() {
        DosefolkQaLog.record(c, DosefolkQaLog.Category.APP, "first")
        DosefolkQaLog.record(c, DosefolkQaLog.Category.APP, "second")
        val lines = DosefolkQaLog.exportFile(c).readLines().filter { it.isNotBlank() }
        assertEquals(2, lines.size)
        val first = JSONObject(lines[0])
        val second = JSONObject(lines[1])
        assertEquals(DosefolkQaLog.currentSessionId(), first.getString("session"))
        assertEquals(first.getString("session"), second.getString("session"))
        assertEquals(BuildConfig.VERSION_NAME, first.getString("appVersion"))
        assertEquals(BuildConfig.VERSION_CODE, first.getInt("appCode"))
        assertTrue(first.has("android"))
        assertTrue(first.has("sdk"))
        assertTrue(first.has("manufacturer"))
        assertTrue(first.has("model"))
    }
}
