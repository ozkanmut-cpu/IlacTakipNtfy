package com.ozkanmut.ilactakip

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.google.crypto.tink.Aead
import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.PublicKeySign
import com.google.crypto.tink.PublicKeyVerify
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkJsonProtoKeysetFormat
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import com.google.crypto.tink.hybrid.HpkeParameters
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.signature.Ed25519Parameters
import com.google.crypto.tink.signature.SignatureConfig
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.lang.reflect.Modifier
import java.math.BigDecimal
import java.security.GeneralSecurityException
import java.util.Base64
import java.util.UUID

/**
 * RED contract for the missing Task 10 codec. All identities/data are TEST-ONLY.
 *
 * Intended API: RelayCrypto(identityStore, localInstallId), seal(JSONObject, recipient public
 * identity, RelayOuterContext), and open(bytes, sender public identity, context). Successful open
 * returns VerifiedPayload.domainPayload (JSONObject); authentication failures throw
 * GeneralSecurityException, never an unverified/partial payload.
 *
 * v1 wire commitment: compact UTF-8 JSON, recursive object keys sorted by UTF-16 code units,
 * array order preserved, null/boolean literals, finite numbers in shortest plain decimal form
 * (no redundant fractional zeroes; negative zero is 0), quote/backslash/control characters escaped,
 * other Unicode preserved without normalization. No whitespace outside strings or trailing LF.
 * The inner object has context, payload, signature (unpadded Base64URL Ed25519), version=1.
 * Sign UTF-8("dosefolk-relay-signature-v1" + NUL) followed by the canonical inner object with the
 * signature member omitted. Tink HPKE contextInfo is UTF-8("dosefolk-relay-hpke-v1" + NUL)
 * followed by the canonical context object. HPKE is RAW X25519/HKDF-SHA256/ChaCha20-Poly1305.
 *
 * Literal bytes/signature below are independent of production serialization. HPKE ciphertext
 * is deliberately NOT deterministic. The test-owned key handles are never obtained from a
 * production identity store: only encrypted synthetic keysets are injected into its real storage.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RelayCryptoTest {
    private lateinit var sender: TestDevice
    private lateinit var recipient: TestDevice
    private lateinit var context: RelayOuterContext
    private val namespace = "TEST-ONLY-relay-crypto-${UUID.randomUUID()}"

    @Before
    fun setUp() {
        AeadConfig.register()
        HybridConfig.register()
        SignatureConfig.register()
        sender = device(SENDER_ID, 7, fixtureSigningKey())
        recipient = device(RECIPIENT_ID, 11)
        context = RelayOuterContext(
            messageId = "TEST-ONLY-message-001",
            routeId = "TEST-ONLY-route-001",
            senderInstallId = SENDER_ID,
            recipientInstallId = RECIPIENT_ID,
            senderKeyVersion = 7,
            recipientKeyVersion = 11
        )
    }

    // Catches platform JSON iteration order, missing signature/domain separation, and wrong framing.
    @Test
    fun sealProducesExactCanonicalInnerBytesAndIndependentEd25519Signature() {
        val encrypted = sender.codec.seal(unorderedPayload(), recipient.identity, context)
        val inner = recipient.decryptForTest(encrypted, EXPECTED_HPKE_CONTEXT.toByteArray(Charsets.UTF_8))

        assertArrayEquals(EXPECTED_INNER.toByteArray(Charsets.UTF_8), inner)
        val signature = Base64.getUrlDecoder().decode(JSONObject(String(inner, Charsets.UTF_8)).getString("signature"))
        assertArrayEquals(Base64.getUrlDecoder().decode(EXPECTED_SIGNATURE), signature)
        sender.signing.publicKeysetHandle.getPrimitive(RegistryConfiguration.get(), PublicKeyVerify::class.java)
            .verify(signature, EXPECTED_SIGNING_BYTES.toByteArray(Charsets.UTF_8))
    }

    // Catches canonicalization that depends on insertion order, including nested objects.
    @Test
    fun equivalentPayloadOrdersProduceTheSameCanonicalSignedInnerEnvelope() {
        val first = sender.codec.seal(unorderedPayload(), recipient.identity, context)
        val second = sender.codec.seal(JSONObject(EXPECTED_PAYLOAD), recipient.identity, context)
        val aad = EXPECTED_HPKE_CONTEXT.toByteArray(Charsets.UTF_8)

        assertArrayEquals(EXPECTED_INNER.toByteArray(Charsets.UTF_8), recipient.decryptForTest(first, aad))
        assertArrayEquals(EXPECTED_INNER.toByteArray(Charsets.UTF_8), recipient.decryptForTest(second, aad))
    }

    // Catches a decoder that verifies a self-produced variant but cannot consume the agreed bytes.
    @Test
    fun openAcceptsIndependentSignedFixtureAndReturnsOnlyVerifiedDomainPayload() {
        val encrypted = recipient.encryptForTest(EXPECTED_INNER.toByteArray(Charsets.UTF_8))

        val verified = recipient.codec.open(encrypted, sender.identity, context)

        assertVerifiedPayload(verified.domainPayload)
    }

    // Catches mismatched seal/open codecs and accidental plaintext transmission.
    @Test
    fun sealAndOpenRoundTripOnlyForTheIntendedRecipientAndContext() {
        val encrypted = sender.codec.seal(unorderedPayload(), recipient.identity, context)

        val verified = recipient.codec.open(encrypted, sender.identity, context)

        assertVerifiedPayload(verified.domainPayload)
        assertFalse(String(encrypted, Charsets.ISO_8859_1).contains("TEST-ONLY-event-001"))
    }

    @Test fun changedMessageIdIsRejected() = rejectsContext(context.copy(messageId = "TEST-ONLY-message-002"))
    @Test fun changedRouteIdIsRejected() = rejectsContext(context.copy(routeId = "TEST-ONLY-route-002"))
    @Test fun changedSenderInstallIdIsRejected() = rejectsContext(context.copy(senderInstallId = "TEST-ONLY-other-sender"))
    @Test fun changedRecipientInstallIdIsRejected() = rejectsContext(context.copy(recipientInstallId = "TEST-ONLY-other-recipient"))
    @Test fun changedSenderKeyVersionIsRejected() = rejectsContext(context.copy(senderKeyVersion = 8))
    @Test fun changedRecipientKeyVersionIsRejected() = rejectsContext(context.copy(recipientKeyVersion = 12))

    // Catches checking fields outside HPKE but failing to cryptographically bind one of them.
    @Test
    fun hpkeAuthenticatesEveryOuterFieldAgainstIndependentContextBytes() {
        val encrypted = sender.codec.seal(unorderedPayload(), recipient.identity, context)
        val mutations = listOf(
            EXPECTED_CONTEXT.replace("TEST-ONLY-message-001", "TEST-ONLY-message-002"),
            EXPECTED_CONTEXT.replace("TEST-ONLY-route-001", "TEST-ONLY-route-002"),
            EXPECTED_CONTEXT.replace(SENDER_ID, "TEST-ONLY-other-sender"),
            EXPECTED_CONTEXT.replace(RECIPIENT_ID, "TEST-ONLY-other-recipient"),
            EXPECTED_CONTEXT.replace("\"senderKeyVersion\":7", "\"senderKeyVersion\":8"),
            EXPECTED_CONTEXT.replace("\"recipientKeyVersion\":11", "\"recipientKeyVersion\":12")
        )
        mutations.forEach { mutated ->
            assertThrows(GeneralSecurityException::class.java) {
                recipient.decryptForTest(encrypted, ("dosefolk-relay-hpke-v1\u0000" + mutated).toByteArray(Charsets.UTF_8))
            }
        }
    }

    // Catches binding only the HPKE layer while ignoring disagreement with the signed inner context.
    @Test
    fun validSignatureAndHpkeStillRejectADifferentSignedInnerContext() {
        val alteredSigningBytes = EXPECTED_SIGNING_BYTES.replace("TEST-ONLY-route-001", "TEST-ONLY-route-002")
        val signature = sender.signing.getPrimitive(RegistryConfiguration.get(), PublicKeySign::class.java)
            .sign(alteredSigningBytes.toByteArray(Charsets.UTF_8))
        val alteredInner = EXPECTED_INNER.replace("TEST-ONLY-route-001", "TEST-ONLY-route-002")
            .replace(EXPECTED_SIGNATURE, base64Url(signature))
        val encrypted = recipient.encryptForTest(alteredInner.toByteArray(Charsets.UTF_8))

        assertThrows(GeneralSecurityException::class.java) { recipient.codec.open(encrypted, sender.identity, context) }
    }

    // Catches a codec that decrypts with a process-global key or trusts install ID without key ownership.
    @Test
    fun sameInstallIdAndVersionButWrongRecipientPrivateKeyCannotOpen() {
        val wrongRecipient = device(RECIPIENT_ID, 11)
        val encrypted = sender.codec.seal(unorderedPayload(), recipient.identity, context)

        assertThrows(GeneralSecurityException::class.java) {
            wrongRecipient.codec.open(encrypted, sender.identity, context)
        }
    }

    // Catches failure to bind local sender/recipient ownership before seal (no laundering context).
    @Test
    fun sealRejectsContextThatDoesNotMatchLocalSenderOrRecipientKeyVersion() {
        listOf(
            context.copy(senderInstallId = "TEST-ONLY-not-local-sender"),
            context.copy(senderKeyVersion = 8),
            context.copy(recipientKeyVersion = 12)
        ).forEach { invalid ->
            assertThrows(GeneralSecurityException::class.java) {
                sender.codec.seal(unorderedPayload(), recipient.identity, invalid)
            }
        }
    }

    // Catches treating authenticated HPKE encryption as sender authentication (anyone can encrypt).
    @Test
    fun wrongPinnedSenderSigningKeyIsRejectedEvenWhenEncryptionMetadataMatches() {
        val impostor = device("TEST-ONLY-impostor", 7)
        val wrongSender = sender.identity.copy(
            signingPublicKey = impostor.identity.signingPublicKey,
            fingerprint = RelayPublicIdentity.fingerprintFor(
                sender.identity.encryptionPublicKey, impostor.identity.signingPublicKey, 7
            )
        )
        val encrypted = recipient.encryptForTest(EXPECTED_INNER.toByteArray(Charsets.UTF_8))

        assertThrows(GeneralSecurityException::class.java) { recipient.codec.open(encrypted, wrongSender, context) }
    }

    // Catches decoding a payload before requiring a valid sender signature.
    @Test
    fun payloadOrSignatureTamperingInsideValidHpkeIsRejected() {
        val badSignature = Base64.getUrlDecoder().decode(EXPECTED_SIGNATURE).also {
            it[0] = (it[0].toInt() xor 1).toByte()
        }
        listOf(
            EXPECTED_INNER.replace("TEST-ONLY-event-001", "TEST-ONLY-event-999"),
            EXPECTED_INNER.replace(EXPECTED_SIGNATURE, base64Url(badSignature))
        ).forEach { inner ->
            val encrypted = recipient.encryptForTest(inner.toByteArray(Charsets.UTF_8))
            assertThrows(GeneralSecurityException::class.java) { recipient.codec.open(encrypted, sender.identity, context) }
        }
    }

    // Catches accepting malformed/truncated ciphertext or releasing plaintext after an AEAD failure.
    @Test
    fun damagedAndTruncatedCiphertextFailClosed() {
        val encrypted = sender.codec.seal(unorderedPayload(), recipient.identity, context)
        val damaged = encrypted.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        listOf(byteArrayOf(), encrypted.copyOf(16), encrypted.copyOf(encrypted.size - 1), damaged).forEach { invalid ->
            assertThrows(GeneralSecurityException::class.java) { recipient.codec.open(invalid, sender.identity, context) }
        }
    }

    @Test
    fun malformedNoncanonicalAndAmbiguousInnerJsonFailClosed() {
        val invalid = listOf(
            " $EXPECTED_INNER", "$EXPECTED_INNER\n", "$EXPECTED_INNER{}",
            EXPECTED_INNER.replace("\"version\":1", "\"version\":1.0"),
            EXPECTED_INNER.replace("\"version\":1", "\"version\":2"),
            EXPECTED_INNER.replace("\"version\":1", "\"version\":1,\"version\":1"),
            EXPECTED_INNER.replace("\"version\":1", "\"unexpected\":0,\"version\":1"),
            EXPECTED_INNER.replace("\"eventId\":", "\"eventId\":\"duplicate\",\"eventId\":"),
            EXPECTED_INNER.replace("\"senderKeyVersion\":7", "\"senderKeyVersion\":\"7\""),
            EXPECTED_INNER.replace(EXPECTED_SIGNATURE, "$EXPECTED_SIGNATURE="),
            EXPECTED_INNER.replace("İlaç 🧪", "\\ud800"),
            EXPECTED_INNER.replace("\"payload\":$EXPECTED_PAYLOAD", "\"payload\":[]")
        )
        invalid.forEach { inner ->
            assertThrows(GeneralSecurityException::class.java) {
                recipient.codec.open(recipient.encryptForTest(inner.toByteArray(Charsets.UTF_8)), sender.identity, context)
            }
        }
        assertThrows(GeneralSecurityException::class.java) {
            recipient.codec.open(recipient.encryptForTest(byteArrayOf(0xc3.toByte(), 0x28)), sender.identity, context)
        }
    }

    @Test
    fun canonicalNumbersRetainExactDecimalValuesWithoutBinary64Roundoff() {
        val payload = JSONObject().put("large", 9007199254740993L)
            .put("decimal", BigDecimal("0.12345678901234567890123456789"))
            .put("zero", -0.0).put("whole", BigDecimal("2.000"))
        assertEquals(
            """{"decimal":0.12345678901234567890123456789,"large":9007199254740993,"whole":2,"zero":0}""",
            RelayEnvelopeFormat.canonical(payload)
        )
        val encrypted = sender.codec.seal(payload, recipient.identity, context)
        val opened = recipient.codec.open(encrypted, sender.identity, context).domainPayload
        assertEquals("9007199254740993", opened.get("large").toString())
        assertEquals("0.12345678901234567890123456789", opened.get("decimal").toString())
    }

    @Test
    fun canonicalUnicodeUsesUtf16OrderWithoutNormalizingKeysOrValues() {
        val payload = JSONObject().put("\ue000", "private-use").put("🧪", "astral")
            .put("é", "composed").put("e\u0301", "decomposed")
        assertEquals("""{"é":"decomposed","é":"composed","🧪":"astral","":"private-use"}""",
            RelayEnvelopeFormat.canonical(payload))
    }

    @Test
    fun oversizedDeepAndInvalidContextInputFailClosed() {
        assertThrows(GeneralSecurityException::class.java) {
            sender.codec.seal(JSONObject().put("data", "x".repeat(512 * 1024)), recipient.identity, context)
        }
        var deep = JSONObject()
        repeat(70) { deep = JSONObject().put("nested", deep) }
        assertThrows(GeneralSecurityException::class.java) { sender.codec.seal(deep, recipient.identity, context) }
        listOf(context.copy(messageId = ""), context.copy(routeId = "bad\nroute"),
            context.copy(senderKeyVersion = 0), context.copy(recipientKeyVersion = -1)).forEach { invalid ->
            assertThrows(GeneralSecurityException::class.java) {
                sender.codec.seal(unorderedPayload(), recipient.identity, invalid)
            }
        }
    }

    @Test
    fun sharedEnvelopeFixtureCommitsCanonicalBytesContextAndSigningContract() {
        val fixture = fixture("relay-envelope-v1.json")
        assertTrue(fixture.getBoolean("testOnly"))
        assertEquals(EXPECTED_INNER, fixture.getString("canonicalInner"))
        assertEquals(EXPECTED_SIGNING_BYTES, fixture.getString("signingInput"))
        assertEquals(EXPECTED_HPKE_CONTEXT, fixture.getString("hpkeContextInfo"))
        val encrypted = recipient.encryptForTest(fixture.getString("canonicalInner").toByteArray(Charsets.UTF_8))
        assertVerifiedPayload(recipient.codec.open(encrypted, sender.identity, context).domainPayload)
        val cases = fixture.getJSONArray("canonicalCases")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            assertEquals(case.getString("canonical"), RelayEnvelopeFormat.canonical(
                RelayEnvelopeFormat.parseCanonical(case.getString("canonical").toByteArray(Charsets.UTF_8))))
        }
    }

    @Test
    fun sharedPairingFixtureVerifiesSchemaAndSignatureButDoesNotActivateTrust() {
        val fixture = fixture("relay-pairing-v2.json")
        assertTrue(fixture.getBoolean("testOnly"))
        val bytes = fixture.getString("canonicalOffer").toByteArray(Charsets.UTF_8)
        val offer = RelayEnvelopeFormat.verifyPairing(bytes)
        assertEquals("TEST-ONLY-offer-001", offer.getString("offerId"))
        assertEquals(2, offer.getInt("version"))
        assertEquals("TEST-ONLY-sender", offer.getString("installId"))
        listOf(
            fixture.getString("canonicalOffer").replace("TEST-ONLY-offer-001", "TEST-ONLY-offer-002"),
            fixture.getString("canonicalOffer").replace("\"version\":2", "\"extra\":true,\"version\":2"),
            fixture.getString("canonicalOffer").replace("\"version\":2", "\"version\":2,\"version\":2")
        ).forEach { invalid ->
            assertThrows(GeneralSecurityException::class.java) {
                RelayEnvelopeFormat.verifyPairing(invalid.toByteArray(Charsets.UTF_8))
            }
        }
    }

    // Security API contract, not private-field reflection: callers must never receive key containers
    // or raw secret bytes. Internal signing/decryption capabilities may return operation results;
    // their Kotlin-internal JVM names contain '$' and are not source-public export methods.
    @Test
    fun publicCodecAndIdentityOperationsDoNotExportPrivateKeyMaterial() {
        listOf(RelayCrypto::class.java, RelayIdentityStore::class.java).forEach { type ->
            type.declaredMethods.filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }.forEach { method ->
                val returned = method.returnType
                assertFalse("${type.simpleName}.${method.name} must not export a key container",
                    KeysetHandle::class.java.isAssignableFrom(returned) ||
                        com.google.crypto.tink.Key::class.java.isAssignableFrom(returned) ||
                        java.security.Key::class.java.isAssignableFrom(returned) ||
                        returned.name.contains("SecretBytes") || returned.name.contains("PrivateKey"))
                if (returned == ByteArray::class.java && '$' !in method.name) {
                    assertEquals("Raw secret export is forbidden", RelayCrypto::class.java, type)
                    assertEquals("Only sealed ciphertext is a public byte-array result", "seal", method.name)
                }
            }
        }
    }

    // Catches key/payload/ciphertext disclosure on success, failed open, exceptions, and diagnostics.
    @Test
    fun successfulAndRejectedOperationsDoNotLogSyntheticSecretsOrPayloads() {
        val captured = ByteArrayOutputStream()
        val previousOut = System.out
        val previousErr = System.err
        val stream = PrintStream(captured, true, "UTF-8")
        ShadowLog.clear()
        var diagnostics = ""
        var encodedCiphertext = ""
        try {
            System.setOut(stream)
            System.setErr(stream)
            val encrypted = sender.codec.seal(unorderedPayload(), recipient.identity, context)
            encodedCiphertext = base64Url(encrypted)
            recipient.codec.open(encrypted, sender.identity, context)
            val rejected = assertThrows(GeneralSecurityException::class.java) {
                recipient.codec.open(encrypted, sender.identity, context.copy(routeId = "TEST-ONLY-wrong-route"))
            }
            diagnostics = listOf(sender.store, recipient.store, sender.codec, recipient.codec, rejected)
                .joinToString("\n") + "\n" + ShadowLog.getLogs().joinToString("\n") { "${it.msg} ${it.throwable}" }
        } finally {
            System.setOut(previousOut)
            System.setErr(previousErr)
            stream.close()
        }
        val observed = diagnostics + captured.toString("UTF-8")
        assertFalse("Logs/diagnostics must not include ciphertext", observed.contains(encodedCiphertext))
        listOf(TEST_ONLY_SIGNING_SEED_HEX, base64Url(hexBytes(TEST_ONLY_SIGNING_SEED_HEX)),
            Base64.getEncoder().encodeToString(hexBytes(TEST_ONLY_SIGNING_SEED_HEX)),
            TEST_ONLY_PRIVATE_PROTO_BASE64, "TEST-ONLY-event-001", "İlaç 🧪").forEach { secret ->
            assertFalse("Sensitive fixture content must not appear in logs/diagnostics", observed.contains(secret))
        }
    }

    private fun rejectsContext(mutated: RelayOuterContext) {
        val encrypted = sender.codec.seal(unorderedPayload(), recipient.identity, context)
        assertThrows(GeneralSecurityException::class.java) { recipient.codec.open(encrypted, sender.identity, mutated) }
    }

    private fun assertVerifiedPayload(payload: JSONObject) {
        assertEquals(setOf("eventId", "nested", "values"), payload.keys().asSequence().toSet())
        assertEquals("TEST-ONLY-event-001", payload.getString("eventId"))
        val nested = payload.getJSONObject("nested")
        assertEquals(setOf("a", "z"), nested.keys().asSequence().toSet())
        assertEquals("İlaç 🧪", nested.getString("a"))
        assertTrue(nested.isNull("z"))
        val values = payload.getJSONArray("values")
        assertEquals(3, values.length())
        assertTrue(values.getBoolean(0))
        assertEquals(2, values.getInt(1))
        assertEquals("line\nquote\"slash\\", values.getString(2))
    }

    private fun unorderedPayload(): JSONObject = JSONObject()
        .put("values", JSONObject(EXPECTED_PAYLOAD).getJSONArray("values"))
        .put("nested", JSONObject().put("z", JSONObject.NULL).put("a", "İlaç 🧪"))
        .put("eventId", "TEST-ONLY-event-001")

    private fun fixture(name: String): JSONObject = requireNotNull(javaClass.classLoader?.getResourceAsStream(name))
        .bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }

    private fun device(installId: String, version: Int, signing: KeysetHandle = KeysetHandle.generateNew(
        Ed25519Parameters.create(Ed25519Parameters.Variant.NO_PREFIX)
    )): TestDevice {
        val application = ApplicationProvider.getApplicationContext<Context>()
        val storageId = UUID.randomUUID().toString()
        val appContext = object : ContextWrapper(application) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences("$namespace-$storageId-$name", mode)
        }
        val encryption = KeysetHandle.generateNew(HpkeParameters.builder()
            .setKemId(HpkeParameters.KemId.DHKEM_X25519_HKDF_SHA256)
            .setKdfId(HpkeParameters.KdfId.HKDF_SHA256)
            .setAeadId(HpkeParameters.AeadId.CHACHA20_POLY1305)
            .setVariant(HpkeParameters.Variant.NO_PREFIX).build())
        val master = KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM)
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
        fun wrapped(handle: KeysetHandle, purpose: String): String = Base64.getEncoder().encodeToString(
            TinkProtoKeysetFormat.serializeEncryptedKeyset(handle, master,
                "dosefolk-relay-keyset-v1\u0000$version\u0000$purpose".toByteArray(Charsets.UTF_8), RegistryConfiguration.get())
        )
        // Test-only preload into the existing encrypted persistence boundary, never a production import API.
        check(appContext.getSharedPreferences("dosefolk_relay_identity", Context.MODE_PRIVATE).edit()
            .putString("encrypted_hpke_keyset", wrapped(encryption, "hpke"))
            .putString("encrypted_ed25519_keyset", wrapped(signing, "ed25519"))
            .putInt("key_version", version).commit())
        val store = RelayIdentityStore(appContext, object : RelayMasterAeadSource {
            override fun exists(): Boolean = true
            override fun createNew(): Aead = throw AssertionError("TEST-ONLY fixture must not regenerate")
            override fun getExisting(): Aead = master
        })
        return TestDevice(store, RelayCrypto(store, installId), store.publicIdentity(), encryption, signing)
    }

    private class TestDevice(
        val store: RelayIdentityStore,
        val codec: RelayCrypto,
        val identity: RelayPublicIdentity,
        val encryption: KeysetHandle,
        val signing: KeysetHandle
    ) {
        fun decryptForTest(ciphertext: ByteArray, aad: ByteArray): ByteArray = encryption
            .getPrimitive(RegistryConfiguration.get(), HybridDecrypt::class.java).decrypt(ciphertext, aad)

        fun encryptForTest(inner: ByteArray): ByteArray = encryption.publicKeysetHandle
            .getPrimitive(RegistryConfiguration.get(), HybridEncrypt::class.java)
            .encrypt(inner, EXPECTED_HPKE_CONTEXT.toByteArray(Charsets.UTF_8))
    }

    companion object {
        private const val SENDER_ID = "TEST-ONLY-sender"
        private const val RECIPIENT_ID = "TEST-ONLY-recipient"
        private const val EXPECTED_CONTEXT = """{"messageId":"TEST-ONLY-message-001","recipientInstallId":"TEST-ONLY-recipient","recipientKeyVersion":11,"routeId":"TEST-ONLY-route-001","senderInstallId":"TEST-ONLY-sender","senderKeyVersion":7}"""
        private const val EXPECTED_PAYLOAD = """{"eventId":"TEST-ONLY-event-001","nested":{"a":"İlaç 🧪","z":null},"values":[true,2,"line\nquote\"slash\\"]}"""
        private const val EXPECTED_SIGNATURE = "qbC5mSL59q0fUdMX9JHUot5Xcuumo1tl4j8Z1z3R8ZSmGEtntMor-biur8fUoqHNx9vj4RZ0WNwFIEEyBRzhCg"
        private const val EXPECTED_HPKE_CONTEXT = "dosefolk-relay-hpke-v1\u0000" + EXPECTED_CONTEXT
        private const val EXPECTED_SIGNING_BYTES = "dosefolk-relay-signature-v1\u0000" +
            "{\"context\":" + EXPECTED_CONTEXT + ",\"payload\":" + EXPECTED_PAYLOAD + ",\"version\":1}"
        private const val EXPECTED_INNER = "{\"context\":" + EXPECTED_CONTEXT + ",\"payload\":" + EXPECTED_PAYLOAD +
            ",\"signature\":\"" + EXPECTED_SIGNATURE + "\",\"version\":1}"

        // PUBLICLY KNOWN RFC 8032 section 7.1 test-vector seed. NEVER USE FOR REAL IDENTITIES.
        // Its public key is d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a.
        // Proto bytes encode only that synthetic Ed25519PrivateKey, version 0, as key_value/public_key.
        private const val TEST_ONLY_SIGNING_SEED_HEX = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"
        private const val TEST_ONLY_PRIVATE_PROTO_BASE64 = "EiCdYbGd7/1aYLqESvSS7CzEREnFaXsyaRlwO6wDHK5/YBoiEiDXWpgBgrEKt9VL/tPJZAc6DuFy89qmIyWvAhpo9wdRGg=="

        // Cleartext parsing is strictly confined to this synthetic test fixture; the production
        // identity store only receives Tink-encrypted keysets protected by the test master AEAD.
        private fun fixtureSigningKey(): KeysetHandle = TinkJsonProtoKeysetFormat.parseKeyset(
            """{"primaryKeyId":1,"key":[{"keyData":{"typeUrl":"type.googleapis.com/google.crypto.tink.Ed25519PrivateKey","value":"$TEST_ONLY_PRIVATE_PROTO_BASE64","keyMaterialType":"ASYMMETRIC_PRIVATE"},"status":"ENABLED","keyId":1,"outputPrefixType":"RAW"}]}""",
            InsecureSecretKeyAccess.get()
        )

        private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        private fun hexBytes(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
