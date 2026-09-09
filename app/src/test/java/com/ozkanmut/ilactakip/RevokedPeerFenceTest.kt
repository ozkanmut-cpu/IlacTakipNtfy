package com.ozkanmut.ilactakip

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RevokedPeerFenceTest {
    private lateinit var c: Context

    @Before
    fun setUp() {
        c = ApplicationProvider.getApplicationContext()
        c.getSharedPreferences("dosefolk_revoked_peers", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun tombstonePersistsUntilExplicitRePairCompletion() {
        val topic = "peer-repair"
        RevokedPeerFence.markRevoked(c, topic)
        assertTrue(RevokedPeerFence.isRevoked(c, topic))

        // A successful drain is intentionally not enough to activate trust again;
        // UI writes the peer first and only then completes the relationship boundary.
        PairingLifecycle.completeRePair(c, topic)
        assertFalse(RevokedPeerFence.isRevoked(c, topic))
    }

    @Test
    fun clearingOnePeerDoesNotReactivateAnotherRevokedPeer() {
        RevokedPeerFence.markRevoked(c, "peer-a")
        RevokedPeerFence.markRevoked(c, "peer-b")

        PairingLifecycle.completeRePair(c, "peer-a")

        assertFalse(RevokedPeerFence.isRevoked(c, "peer-a"))
        assertTrue(RevokedPeerFence.isRevoked(c, "peer-b"))
    }
}
