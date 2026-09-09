package com.ozkanmut.ilactakip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlertOutboxStaleEscalationTest {
    private val now = 1_000_000_000_000L
    private val tenHours = 10L * 60L * 60L * 1000L

    @Test
    fun oldInflightEscalation_isRecoveredBySession() {
        val alert = PendingAlert(
            id = "escalation|2026-09-09|08:00|care-topic|1",
            topic = "care-topic",
            title = "Dosefolk",
            message = "attention",
            createdAt = now - tenHours,
            inFlight = true
        )

        assertEquals(
            EscalationSessionKey("2026-09-09", "08:00"),
            AlertOutbox.staleEscalationSession(alert, now)
        )
    }

    @Test
    fun freshOrNonInflightEscalation_isNotExpired() {
        val fresh = PendingAlert(
            "escalation|2026-09-09|08:00|care-topic|0",
            "care-topic", "Dosefolk", "attention",
            now - tenHours + 1L,
            true
        )
        val queued = fresh.copy(createdAt = now - tenHours - 1L, inFlight = false)

        assertNull(AlertOutbox.staleEscalationSession(fresh, now))
        assertNull(AlertOutbox.staleEscalationSession(queued, now))
    }

    @Test
    fun genericOldInflightAlert_isNeverClassifiedAsEscalation() {
        val alert = PendingAlert(
            id = "circle-revoked-random-id",
            topic = "peer-topic",
            title = "Dosefolk sync",
            message = "{}",
            createdAt = now - tenHours - 1L,
            inFlight = true
        )

        assertNull(AlertOutbox.staleEscalationSession(alert, now))
    }

    @Test
    fun malformedEscalationId_isPreservedForNormalProbePath() {
        val invalidDate = PendingAlert(
            "escalation|not-a-date|08:00|care-topic|0",
            "care-topic", "Dosefolk", "attention",
            now - tenHours - 1L,
            true
        )
        val invalidTime = invalidDate.copy(
            id = "escalation|2026-09-09|99:99|care-topic|0"
        )

        assertNull(AlertOutbox.staleEscalationSession(invalidDate, now))
        assertNull(AlertOutbox.staleEscalationSession(invalidTime, now))
    }
}
