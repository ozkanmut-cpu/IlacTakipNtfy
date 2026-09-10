package com.ozkanmut.ilactakip

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CirclePresenceTest {
    private lateinit var c: Context

    @Before fun setup() {
        c = ApplicationProvider.getApplicationContext()
        listOf("ilac_takip","dosefolk_alert_outbox","dosefolk_events","dosefolk_circle_presence","dosefolk_revoked_peers").forEach {
            c.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        c.getSharedPreferences("ilac_takip", Context.MODE_PRIVATE).edit()
            .putString("topic", "phone-a")
            .putString("people", JSONArray().put(JSONObject().put("id","b").put("name","B").put("topic","phone-b")).toString())
            .commit()
    }

    @Test fun presencePublishesOnActorTopicWithExplicitTarget() {
        CirclePresence.publish(c, "phone-b")
        val rows = JSONArray(c.getSharedPreferences("dosefolk_alert_outbox", Context.MODE_PRIVATE).getString("alerts", "[]"))
        val row = rows.getJSONObject(0)
        val payload = JSONObject(row.getString("message"))
        assertEquals("phone-a", row.getString("topic"))
        assertEquals("phone-a", payload.getString("actorTopic"))
        assertEquals("phone-b", payload.getString("targetTopic"))
        assertEquals("circle_presence", payload.getString("type"))
    }

    @Test fun targetedPresenceConfirmsPeerOnlyOnTargetDevice() {
        val event = DoseEvent("presence-1","circle_presence","circle","B","phone-b",123L,emptyList(),"synced",1L,ownerId="phone-b",targetTopic="phone-a")
        assertFalse(CirclePresence.confirmed(c, "phone-b"))
        CirclePresence.markSeen(c, event)
        assertTrue(CirclePresence.confirmed(c, "phone-b"))
    }

    @Test fun presenceForAnotherDeviceDoesNotConfirmPeer() {
        val event = DoseEvent("presence-2","circle_presence","circle","B","phone-b",123L,emptyList(),"synced",1L,ownerId="phone-b",targetTopic="phone-c")
        CirclePresence.markSeen(c, event)
        assertFalse(CirclePresence.confirmed(c, "phone-b"))
    }

    @Test fun revokeClearsConfirmedPresence() {
        val event = DoseEvent("presence-3","circle_presence","circle","B","phone-b",123L,emptyList(),"synced",1L,ownerId="phone-b",targetTopic="phone-a")
        CirclePresence.markSeen(c, event)
        assertTrue(CirclePresence.confirmed(c, "phone-b"))
        PairingLifecycle.revoke(c, Person("b","B","phone-b"))
        assertFalse(CirclePresence.confirmed(c, "phone-b"))
    }
}
