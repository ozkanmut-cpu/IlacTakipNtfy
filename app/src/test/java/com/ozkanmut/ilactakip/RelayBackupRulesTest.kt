package com.ozkanmut.ilactakip

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RelayBackupRulesTest {
    // Mutation caught: omitting the legacy manifest rules or allowing either relay preference file into backup.
    @Test
    fun manifestLegacyBackupRulesExcludeOnlyRelayIdentityAndTrust() {
        assertEquals(
            mapOf("full-backup-content" to relayPreferenceFiles),
            exclusionsFromManifest("fullBackupContent", "full-backup-content")
        )
    }

    // Mutation caught: excluding relay state from cloud backup but not device transfer, or disabling all app backup.
    @Test
    fun manifestCloudAndDeviceTransferRulesExcludeOnlyRelayIdentityAndTrust() {
        assertEquals(
            mapOf("cloud-backup" to relayPreferenceFiles, "device-transfer" to relayPreferenceFiles),
            exclusionsFromManifest("dataExtractionRulesRes", "data-extraction-rules")
        )
    }

    private fun exclusionsFromManifest(field: String, root: String): Map<String, Set<Pair<String, String>>> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val info = context.applicationInfo
        assertTrue(info.flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0)
        // Read the installed/merged manifest's resource ID, not an assumed R.xml constant.
        val resource = ReflectionHelpers.getField<Int>(info, field)
        assertTrue("The manifest must select backup rules", resource > 0)
        val result = linkedMapOf<String, MutableSet<Pair<String, String>>>()
        context.resources.getXml(resource).use { parser ->
            var section = root
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        root -> assertEquals(1, parser.depth)
                        "cloud-backup", "device-transfer" -> section = parser.name
                        "exclude" -> result.getOrPut(section) { linkedSetOf() }.add(
                            parser.getAttributeValue(null, "domain") to parser.getAttributeValue(null, "path")
                        )
                        // An include whitelist would change backup eligibility for unrelated app data.
                        "include" -> fail("Relay backup rules must not whitelist unrelated app data")
                        else -> fail("Unexpected backup rule element")
                    }
                }
                parser.next()
            }
        }
        return result
    }

    private val relayPreferenceFiles = setOf(
        "sharedpref" to "dosefolk_relay_identity.xml",
        "sharedpref" to "dosefolk_relay_peers.xml"
    )
}
