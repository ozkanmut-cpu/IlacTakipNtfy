package com.ozkanmut.ilactakip

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.GeneralSecurityException
import java.util.Base64
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RelayIdentityStoreTest {
    private lateinit var application: Context
    private lateinit var namespace: String
    private lateinit var masterAead: Aead
    private val createdMasters = mutableSetOf<String>()

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        namespace = "relay-test-${UUID.randomUUID()}"
        createdMasters.clear()
        AeadConfig.register()
        masterAead = newTestMasterAead()
    }

    // Mutation caught: generating a fresh identity whenever the store is reconstructed.
    @Test
    fun localIdentityIsStableAcrossStoreAndContextReconstruction() {
        val first = identityStoreFor("local").publicIdentity()

        val restarted = identityStoreFor("local").publicIdentity()

        assertEquals(first, restarted)
        assertTrue(restarted.keyVersion > 0)
    }

    // Mutation caught: exporting a keyset/private field or substituting a serialized keyset for a public key.
    @Test
    fun publicExportContainsExactlyTheFourPublicIdentityFields() {
        val identity = identityStoreFor("local").publicIdentity()
        val exported = JSONObject(identity.toJson().toString())

        assertEquals(
            setOf("encryptionPublicKey", "signingPublicKey", "keyVersion", "fingerprint"),
            exported.keys().asSequence().toSet()
        )
        assertEquals(identity.encryptionPublicKey, exported.getString("encryptionPublicKey"))
        assertEquals(identity.signingPublicKey, exported.getString("signingPublicKey"))
        assertEquals(identity.keyVersion, exported.getInt("keyVersion"))
        assertEquals(identity.fingerprint, exported.getString("fingerprint"))
        assertCanonicalPublicKey(exported.getString("encryptionPublicKey"))
        assertCanonicalPublicKey(exported.getString("signingPublicKey"))
        assertTrue(identity.keyVersion > 0)
        assertTrue(identity.fingerprint.matches(Regex("[0-9a-f]{64}")))
    }

    // Mutation caught: reusing one public key for encryption/signing or a process-global identity across installs.
    @Test
    fun localIdentityHasSeparateEncryptionAndSigningKeysUniqueToEachInstall() {
        val first = identityStoreFor("install-a").publicIdentity()
        val second = identityStoreFor("install-b").publicIdentity()

        listOf(first, second).forEach { identity ->
            assertCanonicalPublicKey(identity.encryptionPublicKey)
            assertCanonicalPublicKey(identity.signingPublicKey)
            assertNotEquals(identity.encryptionPublicKey, identity.signingPublicKey)
        }
        assertNotEquals(first.encryptionPublicKey, second.encryptionPublicKey)
        assertNotEquals(first.signingPublicKey, second.signingPublicKey)
        assertNotEquals(first.fingerprint, second.fingerprint)
    }

    // Mutation caught: omitting/reordering a public field or dropping domain/version separation from the digest.
    @Test
    fun fingerprintIsSha256OfTheExplicitCanonicalPublicBundle() {
        val identity = identityStoreFor("local").publicIdentity()

        assertEquals(
            expectedFingerprint(identity.encryptionPublicKey, identity.signingPublicKey, identity.keyVersion),
            identity.fingerprint
        )
    }

    // Mutation caught: persisting only some identity fields or treating keyVersion as an ordering counter.
    @Test
    fun peerPinPersistsTheCompleteTupleAndAnOpaquePositiveVersion() {
        val identity = fixtureIdentity(keyVersion = Int.MAX_VALUE)
        val store = RelayPeerStore(contextFor("local"))

        assertTrue(store.pin("peer-a", identity))

        val restarted = RelayPeerStore(contextFor("local"))
        assertEquals(identity, restarted.pinnedIdentity("peer-a"))
        assertTrue(restarted.isTrusted("peer-a", identity))
        assertFalse(restarted.isRevoked("peer-a"))
        assertNull(restarted.pinnedIdentity("peer-b"))
        assertFalse(restarted.isTrusted("peer-b", identity))
    }

    // Mutation caught: rejecting repeated authenticated pairing metadata or replacing the existing pin on repetition.
    @Test
    fun pinningTheSameIdentityIsIdempotentAcrossReconstruction() {
        val identity = fixtureIdentity()
        assertTrue(RelayPeerStore(contextFor("local")).pin("peer-a", identity))
        val restarted = RelayPeerStore(contextFor("local"))

        assertTrue(restarted.pin("peer-a", identity.copy()))
        assertTrue(restarted.pin("peer-a", identity.copy()))

        assertEquals(identity, RelayPeerStore(contextFor("local")).pinnedIdentity("peer-a"))
        assertTrue(restarted.isTrusted("peer-a", identity))
    }

    // Mutation caught: trusting or automatically pinning server metadata for an install that has never paired.
    @Test
    fun unpinnedServerMetadataIsNotTrustedAndDoesNotCreateAPin() {
        val store = RelayPeerStore(contextFor("local"))

        assertFalse(store.isTrusted("unknown-peer", fixtureIdentity()))

        assertNull(RelayPeerStore(contextFor("local")).pinnedIdentity("unknown-peer"))
    }

    // Mutation caught: silently accepting a substituted encryption key even with its correctly recomputed fingerprint.
    @Test
    fun encryptionKeyMismatchIsRejectedWithoutOverwritingThePin() {
        assertRejectedWithoutChangingPin(fixtureIdentity(encryptionPublicKey = ENCRYPTION_B))
    }

    // Mutation caught: checking only the encryption key and ignoring a substituted signing key.
    @Test
    fun signingKeyMismatchIsRejectedWithoutOverwritingThePin() {
        assertRejectedWithoutChangingPin(fixtureIdentity(signingPublicKey = SIGNING_B))
    }

    // Mutation caught: automatically adopting a server-advertised key version without signed rotation or re-pairing.
    @Test
    fun keyVersionMismatchIsRejectedWithoutOverwritingThePin() {
        listOf(6, 8).forEach { version ->
            assertRejectedWithoutChangingPin(fixtureIdentity(keyVersion = version))
        }
    }

    // Mutation caught: ignoring the supplied fingerprint when keys and version still match the pin.
    @Test
    fun fingerprintMismatchIsRejectedWithoutOverwritingThePin() {
        assertRejectedWithoutChangingPin(fixtureIdentity().copy(fingerprint = "0".repeat(64)))
    }

    // Mutation caught: looking up trust by key material rather than binding it to the paired install ID.
    @Test
    fun aPinnedIdentityCannotAuthenticateUnderADifferentInstallId() {
        val identity = fixtureIdentity()
        val store = RelayPeerStore(contextFor("local"))
        assertTrue(store.pin("peer-a", identity))

        assertFalse(store.isTrusted("peer-b", identity))

        assertNull(store.pinnedIdentity("peer-b"))
        assertEquals(identity, store.pinnedIdentity("peer-a"))
        assertTrue(store.isTrusted("peer-a", identity))
    }

    // Mutation caught: dropping a revocation on reconstruction or continuing to trust an exact-but-revoked identity.
    @Test
    fun revokedPeerIsRejectedAndItsTombstoneSurvivesReconstruction() {
        val identity = fixtureIdentity()
        val store = RelayPeerStore(contextFor("local"))
        assertTrue(store.pin("peer-a", identity))

        assertTrue(store.revoke("peer-a"))
        assertFalse(store.isTrusted("peer-a", identity))

        val restarted = RelayPeerStore(contextFor("local"))
        assertTrue(restarted.isRevoked("peer-a"))
        assertFalse(restarted.isTrusted("peer-a", identity))
        assertEquals(identity, restarted.pinnedIdentity("peer-a"))
    }

    // Mutation caught: clearing a tombstone when pin is called again with either the same or a replacement identity.
    @Test
    fun pinCannotSilentlyReactivateARevokedPeer() {
        val original = fixtureIdentity()
        val replacement = fixtureIdentity(ENCRYPTION_B, SIGNING_B, 8)
        val store = RelayPeerStore(contextFor("local"))
        assertTrue(store.pin("peer-a", original))
        assertTrue(store.revoke("peer-a"))
        val restarted = RelayPeerStore(contextFor("local"))

        assertFalse(restarted.pin("peer-a", original))
        assertFalse(restarted.pin("peer-a", replacement))

        val afterRepin = RelayPeerStore(contextFor("local"))
        assertTrue(afterRepin.isRevoked("peer-a"))
        assertFalse(afterRepin.isTrusted("peer-a", original))
        assertFalse(afterRepin.isTrusted("peer-a", replacement))
        assertEquals(original, afterRepin.pinnedIdentity("peer-a"))
    }

    // Mutation caught: revoking every peer instead of the named install, or toggling revocation off on a repeat call.
    @Test
    fun revocationIsIdempotentAndDoesNotRevokeOtherPeers() {
        val identityA = fixtureIdentity()
        val identityB = fixtureIdentity(ENCRYPTION_B, SIGNING_B, 9)
        val store = RelayPeerStore(contextFor("local"))
        assertTrue(store.pin("peer-a", identityA))
        assertTrue(store.pin("peer-b", identityB))

        assertTrue(store.revoke("peer-a"))
        assertTrue(store.revoke("peer-a"))

        val restarted = RelayPeerStore(contextFor("local"))
        assertTrue(restarted.isRevoked("peer-a"))
        assertFalse(restarted.isTrusted("peer-a", identityA))
        assertFalse(restarted.isRevoked("peer-b"))
        assertTrue(restarted.isTrusted("peer-b", identityB))
        assertEquals(identityB, restarted.pinnedIdentity("peer-b"))
    }

    // Mutation caught: discarding revocation for an unknown install and accepting a delayed pairing afterward.
    @Test
    fun revocationBeforePinningStillBlocksDelayedPinAfterReconstruction() {
        assertTrue(RelayPeerStore(contextFor("local")).revoke("not-yet-pinned"))
        val restarted = RelayPeerStore(contextFor("local"))

        assertTrue(restarted.isRevoked("not-yet-pinned"))
        assertFalse(restarted.pin("not-yet-pinned", fixtureIdentity()))
        assertFalse(restarted.isTrusted("not-yet-pinned", fixtureIdentity()))
        assertNull(restarted.pinnedIdentity("not-yet-pinned"))
    }

    // Mutation caught: accepting empty/control-character install IDs or normalizing malformed IDs into a valid pin.
    @Test
    fun emptyAndMalformedInstallIdsFailClosed() {
        val identity = fixtureIdentity()
        val store = RelayPeerStore(contextFor("local"))
        assertTrue(store.pin("peer-a", identity))

        listOf("", " ", "\t\n", "peer-a\u0000", "peer-a\n").forEach { invalid ->
            assertFalse(store.pin(invalid, identity))
            assertFalse(store.isTrusted(invalid, identity))
            assertFalse(store.revoke(invalid))
            assertFalse(store.isRevoked(invalid))
            assertNull(store.pinnedIdentity(invalid))
        }

        assertEquals(identity, RelayPeerStore(contextFor("local")).pinnedIdentity("peer-a"))
        assertTrue(store.isTrusted("peer-a", identity))
    }

    // Mutation caught: storing malformed keys/versions or an invalid/inconsistent fingerprint before validation.
    @Test
    fun malformedPublicIdentitiesCannotCreateOrOverwritePins() {
        val original = fixtureIdentity()
        val store = RelayPeerStore(contextFor("local"))
        assertTrue(store.pin("peer-a", original))
        val malformedKeys = listOf("", " ", "%%%", "A".repeat(43),
            base64Url(ByteArray(31) { 1 }), base64Url(ByteArray(33) { 1 }), "$ENCRYPTION_A=")
        val malformed = malformedKeys.flatMap { key ->
            listOf(fixtureIdentity(encryptionPublicKey = key), fixtureIdentity(signingPublicKey = key))
        } + listOf(
            fixtureIdentity(keyVersion = 0),
            fixtureIdentity(keyVersion = -1),
            original.copy(fingerprint = ""),
            original.copy(fingerprint = "z".repeat(64)),
            original.copy(fingerprint = original.fingerprint.uppercase()),
            original.copy(fingerprint = "0".repeat(64))
        )

        malformed.forEachIndexed { index, invalid ->
            val unpinnedId = "invalid-peer-$index"
            assertFalse("Malformed identity $index must not be pinned", store.pin(unpinnedId, invalid))
            assertFalse(store.isTrusted(unpinnedId, invalid))
            assertNull(store.pinnedIdentity(unpinnedId))
            assertFalse(store.pin("peer-a", invalid))
            assertFalse(store.isTrusted("peer-a", invalid))
            assertEquals(original, store.pinnedIdentity("peer-a"))
        }

        val restarted = RelayPeerStore(contextFor("local"))
        assertEquals(original, restarted.pinnedIdentity("peer-a"))
        assertTrue(restarted.isTrusted("peer-a", original))
        malformed.indices.forEach { assertNull(restarted.pinnedIdentity("invalid-peer-$it")) }
    }

    // Mutation caught: swallowing master-key creation failure and persisting an unprotected identity.
    @Test
    fun unavailableMasterCannotCreateIdentityOrPersistAnything() {
        val unavailable = object : RelayMasterAeadSource {
            override fun exists(): Boolean = false
            override fun createNew(): Aead = throw GeneralSecurityException("Test master unavailable")
            override fun getExisting(): Aead = throw AssertionError("No identity exists yet")
        }

        assertThrows(GeneralSecurityException::class.java) {
            RelayIdentityStore(contextFor("local"), unavailable).publicIdentity()
        }

        assertTrue(identityPreferences().all.isEmpty())
    }

    // Mutation caught: regenerating an existing identity or falling back when its wrapping key cannot be opened.
    @Test
    fun unavailableMasterOnRestartDoesNotReplaceThePersistentIdentity() {
        val original = identityStoreFor("local").publicIdentity()
        val before = identityPreferences().all
        val unavailable = object : RelayMasterAeadSource {
            override fun exists(): Boolean = true
            override fun createNew(): Aead = throw AssertionError("Restart must not generate a wrapping key")
            override fun getExisting(): Aead = throw GeneralSecurityException("Test master unavailable")
        }

        assertThrows(GeneralSecurityException::class.java) {
            RelayIdentityStore(contextFor("local"), unavailable).publicIdentity()
        }

        assertTrue("Encrypted persistent state must remain unchanged", before == identityPreferences().all)
        assertEquals(original, identityStoreFor("local").publicIdentity())
    }

    // Mutation caught: accepting an encrypted keyset with the wrong master or silently generating a new identity.
    @Test
    fun wrongMasterCannotOpenOrReplaceThePersistentIdentity() {
        val original = identityStoreFor("local").publicIdentity()
        val before = identityPreferences().all

        assertThrows(GeneralSecurityException::class.java) {
            RelayIdentityStore(contextFor("local"), existingMasterOnly(newTestMasterAead())).publicIdentity()
        }

        assertTrue("Encrypted persistent state must remain unchanged", before == identityPreferences().all)
        assertEquals(original, identityStoreFor("local").publicIdentity())
    }

    // Mutation caught: omitting the purpose/version AAD or persisting a cleartext private keyset.
    @Test
    fun persistedKeysetsAreEncryptedWithTheExplicitPurposeAndVersionContext() {
        val identity = identityStoreFor("local").publicIdentity()
        val preferences = identityPreferences()
        listOf("encrypted_hpke_keyset" to "hpke", "encrypted_ed25519_keyset" to "ed25519").forEach { (key, purpose) ->
            val ciphertext = Base64.getDecoder().decode(preferences.getString(key, null)!!)
            val aad = "dosefolk-relay-keyset-v1\u0000${identity.keyVersion}\u0000$purpose".toByteArray(Charsets.UTF_8)
            // Consumer-side encrypted parse proves the store wrote an encrypted Tink keyset with this exact AAD.
            val handle = TinkProtoKeysetFormat.parseEncryptedKeyset(ciphertext, masterAead, aad, RegistryConfiguration.get())
            assertEquals(1, handle.publicKeysetHandle.size())
            assertThrows(GeneralSecurityException::class.java) {
                TinkProtoKeysetFormat.parseEncryptedKeyset(ciphertext, masterAead, byteArrayOf(), RegistryConfiguration.get())
            }
        }
    }

    // Mutation caught: trusting a modified positive version without authenticating it against the stored keysets.
    @Test
    fun alteredKeyVersionFailsClosedWithoutReplacingStoredKeysets() {
        identityStoreFor("local").publicIdentity()
        val preferences = identityPreferences()
        assertTrue(preferences.edit().putInt("key_version", 2).commit())
        val tampered = preferences.all

        assertThrows(GeneralSecurityException::class.java) { identityStoreFor("local").publicIdentity() }

        assertTrue("Failed load must not rewrite persisted state", tampered == preferences.all)
    }

    // Mutation caught: silently generating replacement v1 keys when identity preferences disappear but the master survives.
    @Test
    fun lostIdentityPreferencesWithSurvivingMasterFailClosedWithoutWrites() {
        identityStoreFor("local").publicIdentity()
        assertTrue(identityPreferences().edit().clear().commit())

        assertThrows(GeneralSecurityException::class.java) { identityStoreFor("local", cold = true).publicIdentity() }

        assertTrue(coldContextFor("local").getSharedPreferences("dosefolk_relay_identity", 0).all.isEmpty())
    }

    // Mutation caught: bootstrapping a new identity into surviving peer trust when the local identity and master are gone.
    @Test
    fun survivingPeerTrustPreventsFreshIdentityBootstrap() {
        assertTrue(RelayPeerStore(contextFor("local")).pin("peer-a", fixtureIdentity()))

        assertThrows(GeneralSecurityException::class.java) { identityStoreFor("local", cold = true).publicIdentity() }

        assertTrue(identityPreferences().all.isEmpty())
        assertTrue(RelayPeerStore(coldContextFor("local")).isTrusted("peer-a", fixtureIdentity()))
    }

    // Mutation caught: keeping identity/pin state only in Android's SharedPreferences cache instead of durable files.
    @Test
    fun identityAndPeerPinSurviveFreshPreferencesInstancesLoadedFromDisk() {
        val original = identityStoreFor("local").publicIdentity()
        val peer = fixtureIdentity()
        assertTrue(RelayPeerStore(contextFor("local")).pin("peer-a", peer))
        val cold = coldContextFor("local")
        assertNotSame(identityPreferences(), cold.getSharedPreferences("dosefolk_relay_identity", 0))
        assertNotSame(contextFor("local").getSharedPreferences("dosefolk_relay_peers", 0),
            cold.getSharedPreferences("dosefolk_relay_peers", 0))

        assertEquals(original, RelayIdentityStore(cold, existingMasterOnly(masterAead)).publicIdentity())
        assertEquals(peer, RelayPeerStore(cold).pinnedIdentity("peer-a"))
        assertTrue(RelayPeerStore(cold).isTrusted("peer-a", peer))
    }

    // Mutation caught: retaining a revocation only in memory or allowing re-pin after a disk-only reconstruction.
    @Test
    fun revocationSurvivesFreshPreferencesInstancesLoadedFromDisk() {
        val peer = fixtureIdentity()
        val store = RelayPeerStore(contextFor("local"))
        assertTrue(store.pin("peer-a", peer))
        assertTrue(store.revoke("peer-a"))

        val coldStore = RelayPeerStore(coldContextFor("local"))
        assertTrue(coldStore.isRevoked("peer-a"))
        assertFalse(coldStore.isTrusted("peer-a", peer))
        assertFalse(coldStore.pin("peer-a", peer))
    }

    // Mutation caught: exporting an uncommitted identity or silently retrying a half-completed bootstrap under its orphan master.
    @Test
    fun failedIdentityCommitCannotExportUncommittedOrReplacementIdentity() {
        val preferences = identityPreferences()
        val restoreWrites = failDiskWrites(preferences)
        try {
            assertThrows(IOException::class.java) { identityStoreFor("local").publicIdentity() }
            assertTrue("Failed commit must not expose its in-memory identity", preferences.all.isEmpty())
        } finally {
            restoreWrites()
        }

        assertThrows(GeneralSecurityException::class.java) { identityStoreFor("local").publicIdentity() }
        assertThrows(GeneralSecurityException::class.java) { identityStoreFor("local", cold = true).publicIdentity() }
        assertTrue(coldContextFor("local").getSharedPreferences("dosefolk_relay_identity", 0).all.isEmpty())
    }

    // Mutation caught: returning false for a failed pin while its memory-only record still authenticates the peer.
    @Test
    fun failedPinCommitNeverGrantsTrustInMemoryOrAfterColdReload() {
        val peer = fixtureIdentity()
        val preferences = contextFor("local").getSharedPreferences("dosefolk_relay_peers", 0)
        val restoreWrites = failDiskWrites(preferences)
        try {
            assertFalse(RelayPeerStore(contextFor("local")).pin("peer-a", peer))
            assertFalse(RelayPeerStore(contextFor("local")).isTrusted("peer-a", peer))
            assertNull(RelayPeerStore(contextFor("local")).pinnedIdentity("peer-a"))
        } finally {
            restoreWrites()
        }

        assertFalse(RelayPeerStore(coldContextFor("local")).isTrusted("peer-a", peer))
        assertNull(RelayPeerStore(coldContextFor("local")).pinnedIdentity("peer-a"))
    }

    // Mutation caught: trusting a peer after a failed revoke, or treating a memory-only repeated revoke as durably committed.
    @Test
    fun failedRevokeReportsNondurabilityButFencesTrustUntilADurableRetry() {
        val peer = fixtureIdentity()
        val store = RelayPeerStore(contextFor("local"))
        assertTrue(store.pin("peer-a", peer))
        val preferences = contextFor("local").getSharedPreferences("dosefolk_relay_peers", 0)
        val restoreWrites = failDiskWrites(preferences)
        try {
            assertFalse(store.revoke("peer-a"))
            val reconstructed = RelayPeerStore(contextFor("local"))
            assertTrue(reconstructed.isRevoked("peer-a"))
            assertFalse(reconstructed.isTrusted("peer-a", peer))
            assertFalse(reconstructed.pin("peer-a", peer))
            assertFalse(reconstructed.revoke("peer-a"))
        } finally {
            restoreWrites()
        }

        assertTrue(store.revoke("peer-a"))
        assertTrue(RelayPeerStore(coldContextFor("local")).isRevoked("peer-a"))
        assertFalse(RelayPeerStore(coldContextFor("local")).isTrusted("peer-a", peer))
    }

    // Mutation caught: exposing a memory-only replacement pin or cleared tombstone after the
    // single authenticated re-pair commit fails to reach disk.
    @Test
    fun failedAuthenticatedRePairCommitRestoresTheOldPinAndTombstone() {
        val original = fixtureIdentity()
        val replacement = fixtureIdentity(ENCRYPTION_B, SIGNING_B, 8)
        val store = RelayPeerStore(contextFor("local"))
        assertTrue(store.pin("peer-a", original))
        assertTrue(store.revoke("peer-a"))
        val preferences = contextFor("local").getSharedPreferences("dosefolk_relay_peers", 0)
        val restoreWrites = failDiskWrites(preferences)
        try {
            assertFalse(store.pinAfterAuthenticatedPairing("peer-a", replacement))
            val reconstructed = RelayPeerStore(contextFor("local"))
            assertEquals(original, reconstructed.pinnedIdentity("peer-a"))
            assertTrue(reconstructed.isRevoked("peer-a"))
            assertFalse(reconstructed.isTrusted("peer-a", replacement))
        } finally {
            restoreWrites()
        }

        val cold = RelayPeerStore(coldContextFor("local"))
        assertEquals(original, cold.pinnedIdentity("peer-a"))
        assertTrue(cold.isRevoked("peer-a"))
        assertFalse(cold.isTrusted("peer-a", replacement))
    }

    private fun identityStoreFor(install: String, cold: Boolean = false): RelayIdentityStore =
        RelayIdentityStore(if (cold) coldContextFor(install) else contextFor(install), object : RelayMasterAeadSource {
            override fun exists(): Boolean = install in createdMasters
            override fun createNew(): Aead {
                check(createdMasters.add(install)) { "Test master must not be replaced" }
                return masterAead
            }
            override fun getExisting(): Aead = masterAead
        })

    // Real Android SharedPreferencesImpl, constructed from the same file with no Context cache/map reuse.
    // The constructor and backing-file fields are verified against Android 15, matching @Config above.
    private fun coldContextFor(install: String): Context = object : ContextWrapper(contextFor(install)) {
        private val opened = mutableMapOf<String, SharedPreferences>()
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = opened.getOrPut(name) {
            val warm = baseContext.getSharedPreferences(name, mode)
            val file = ReflectionHelpers.getField<File>(warm, "mFile")
            val constructor = Class.forName("android.app.SharedPreferencesImpl")
                .getDeclaredConstructor(File::class.java, Int::class.javaPrimitiveType!!)
            constructor.isAccessible = true
            constructor.newInstance(file, mode) as SharedPreferences
        }
    }

    // Fault only the real disk destination. Android still performs commitToMemory and returns its
    // real commit() failure, unlike a mock returning false without the relevant memory side effects.
    private fun failDiskWrites(preferences: SharedPreferences): () -> Unit {
        preferences.all // Finish its asynchronous initial disk load before changing the destination.
        val file = ReflectionHelpers.getField<File>(preferences, "mFile")
        val backup = ReflectionHelpers.getField<File>(preferences, "mBackupFile")
        val nonDirectory = File(application.cacheDir, "relay-write-fault-${UUID.randomUUID()}")
        check(nonDirectory.createNewFile())
        ReflectionHelpers.setField(preferences, "mFile", File(nonDirectory, "prefs.xml"))
        ReflectionHelpers.setField(preferences, "mBackupFile", File(nonDirectory, "prefs.xml.bak"))
        return {
            ReflectionHelpers.setField(preferences, "mFile", file)
            ReflectionHelpers.setField(preferences, "mBackupFile", backup)
        }
    }

    private fun existingMasterOnly(aead: Aead): RelayMasterAeadSource = object : RelayMasterAeadSource {
        override fun exists(): Boolean = true
        override fun createNew(): Aead = throw AssertionError("Existing identity must not be regenerated")
        override fun getExisting(): Aead = aead
    }

    private fun identityPreferences(): SharedPreferences =
        contextFor("local").getSharedPreferences("dosefolk_relay_identity", Context.MODE_PRIVATE)

    // The JVM lacks AndroidKeyStore. Replace only that platform boundary with a real Tink AEAD;
    // identity generation, encrypted keyset serialization/parsing, and Android persistence remain real.
    private fun newTestMasterAead(): Aead = KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM)
        .getPrimitive(RegistryConfiguration.get(), Aead::class.java)

    private fun assertRejectedWithoutChangingPin(candidate: RelayPublicIdentity) {
        val original = fixtureIdentity()
        val store = RelayPeerStore(contextFor("local"))
        assertTrue(store.pin("peer-a", original))

        assertFalse(store.isTrusted("peer-a", candidate))
        assertEquals(original, store.pinnedIdentity("peer-a"))
        assertFalse(store.pin("peer-a", candidate))

        val restarted = RelayPeerStore(contextFor("local"))
        assertEquals(original, restarted.pinnedIdentity("peer-a"))
        assertTrue(restarted.isTrusted("peer-a", original))
        assertFalse(restarted.isTrusted("peer-a", candidate))
    }

    private fun fixtureIdentity(
        encryptionPublicKey: String = ENCRYPTION_A,
        signingPublicKey: String = SIGNING_A,
        keyVersion: Int = 7
    ) = RelayPublicIdentity(
        encryptionPublicKey = encryptionPublicKey,
        signingPublicKey = signingPublicKey,
        keyVersion = keyVersion,
        fingerprint = expectedFingerprint(encryptionPublicKey, signingPublicKey, keyVersion)
    )

    // Wire contract: UTF-8(label NUL decimal-version NUL encryption-key NUL signing-key),
    // with no trailing NUL. Keys are unpadded Base64URL raw 32-byte public values;
    // fingerprint is lowercase SHA-256 hex. Install ID is separately bound by the peer pin.
    // Deliberately independent: do not call a production canonicalization/fingerprint helper here.
    private fun expectedFingerprint(encryptionPublicKey: String, signingPublicKey: String, keyVersion: Int): String {
        val canonicalBytes = ("dosefolk-relay-identity-v1\u0000" +
            keyVersion.toString() + "\u0000" + encryptionPublicKey + "\u0000" + signingPublicKey)
            .toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(canonicalBytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun assertCanonicalPublicKey(encoded: String) {
        assertTrue(encoded.matches(Regex("[A-Za-z0-9_-]{43}")))
        val bytes = Base64.getUrlDecoder().decode(encoded)
        assertEquals(32, bytes.size)
        assertEquals(encoded, base64Url(bytes))
        assertTrue(bytes.any { it != 0.toByte() })
    }

    // Only namespace real Android preferences: no fake or mocked SharedPreferences.
    // Fresh wrappers simulate reconstructing a store; a different install gets isolated persistence.
    private fun contextFor(install: String): Context = object : ContextWrapper(application) {
        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("$namespace-$install-$name", mode)
    }

    companion object {
        // Public-only RFC 7748 X25519 and RFC 8032 Ed25519 test vectors; no private fixtures.
        private val ENCRYPTION_A = publicHex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")
        private val ENCRYPTION_B = publicHex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
        private val SIGNING_A = publicHex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
        private val SIGNING_B = publicHex("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c")

        private fun publicHex(hex: String): String =
            base64Url(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())

        private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
