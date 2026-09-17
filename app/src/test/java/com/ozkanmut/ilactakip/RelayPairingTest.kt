package com.ozkanmut.ilactakip

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.signature.SignatureConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
import java.security.GeneralSecurityException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

/**
 * RED contract for the missing Task 11 authenticated-pairing client.
 *
 * Intended API:
 * - RelayPairing(context, localInstallId, identityStore, peerStore, transport, clock, randomness)
 * - createOffer(): String returns canonical signed pairing-v2 QR JSON after POST /v1/pairing/offers.
 * - acceptOffer(creatorQr): String verifies the creator QR, POSTs accept, pins only that QR identity,
 *   durably consumes the offer, and returns a canonical peer-signed QR for the creator's second scan.
 * - confirmOffer(peerQr): RelayPairedPeer requires that second signed QR to match the locally created
 *   offer, durably stages its pin before POST confirm, activates a matching revoke fence only after
 *   exact success, and durably consumes it.
 * - RelayPairingTransport.post(path, body) is an injected already-authenticated HTTPS boundary.
 * - RelayPairingRandom.bytes(count) and java.time.Clock are injected for deterministic tests.
 *
 * Pairing proof/hash bytes are the Task 5 server contract. Tests commit independent literal outputs;
 * production must use platform/Tink/JCA primitives, and QR signing/verification must reuse Task 9's
 * RelayIdentityStore operations and Task 10's RelayEnvelopeFormat contract rather than new crypto.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RelayPairingTest {
    private lateinit var application: Context
    private lateinit var namespace: String
    private lateinit var creator: TestDevice
    private lateinit var peer: TestDevice

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        namespace = "TEST-ONLY-relay-pairing-${UUID.randomUUID()}"
        AeadConfig.register()
        HybridConfig.register()
        SignatureConfig.register()
        creator = device(CREATOR_ID)
        peer = device(PEER_ID)
    }

    // Mutations caught: wrong Task 5 hash/HMAC framing or roles, persisting/sending plaintext secret,
    // trusting a server actor field, non-256-bit randomness, and any expiry other than exactly 10m.
    @Test
    fun createUsesExactTask5ProofsAndProducesTask10SignedQrWithTenMinuteExpiry() {
        val transport = RecordingTransport { path, body ->
            assertEquals("/v1/pairing/offers", path)
            assertJsonFields(body, "offerId", "secretHash", "creatorProof")
            assertEquals(OFFER_ID, body.getString("offerId"))
            assertEquals(SECRET_HASH, body.getString("secretHash"))
            assertEquals(CREATOR_PROOF, body.getString("creatorProof"))
            assertFalse(body.toString().contains(PAIRING_SECRET))
            response(201, createdBody(CREATOR_ID))
        }

        val qr = creator.pairing(transport).createOffer()

        assertEquals(1, transport.posts.size)
        val verified = RelayEnvelopeFormat.verifyPairing(qr.toByteArray(Charsets.UTF_8))
        assertEquals(2, verified.getInt("version"))
        assertEquals(OFFER_ID, verified.getString("offerId"))
        assertEquals(CREATOR_ID, verified.getString("installId"))
        assertEquals(creator.identity.encryptionPublicKey, verified.getString("encryptionPublicKey"))
        assertEquals(creator.identity.signingPublicKey, verified.getString("signingPublicKey"))
        assertEquals(creator.identity.keyVersion, verified.getInt("keyVersion"))
        assertEquals(PAIRING_SECRET, verified.getString("pairingSecret"))
        assertEquals(EXPIRES_AT_TEXT, verified.getString("expiresAt"))
    }

    // Mutations caught: regenerating an offer after a transient create failure, consuming fresh
    // randomness on reconstruction, or reposting an already registered unexpired local offer.
    @Test
    fun createRetryReusesTheDurableOfferAndRegisteredQrAcrossReconstruction() {
        var attempts = 0
        val transport = RecordingTransport { _, _ ->
            attempts++
            if (attempts == 1) throw java.io.IOException("synthetic transport failure")
            response(201, createdBody(CREATOR_ID))
        }

        assertThrows(GeneralSecurityException::class.java) { creator.pairing(transport).createOffer() }
        val retried = creator.reconstructed().pairing(
            transport,
            clockAt(NOW.plusSeconds(1)),
            ScriptedRandom()
        ).createOffer()
        val reused = creator.reconstructed().pairing(
            transport,
            clockAt(NOW.plusSeconds(2)),
            ScriptedRandom()
        ).createOffer()

        assertEquals(2, attempts)
        assertEquals(retried, reused)
        assertEquals(OFFER_ID, RelayEnvelopeFormat.verifyPairing(reused.toByteArray()).getString("offerId"))
    }

    // Mutation caught: treating a generic conflict as recovery instead of requiring the server's
    // exact strict success schema after a create response was lost.
    @Test
    fun createResponseLossRetriesTheSameTranscriptButGenericConflictStillFails() {
        var attempts = 0
        val transport = RecordingTransport { _, _ ->
            attempts++
            when (attempts) {
                1 -> throw java.io.IOException("synthetic response loss after server commit")
                2 -> response(409, JSONObject().put("error", "pairing_unavailable"))
                else -> response(201, createdBody(CREATOR_ID))
            }
        }

        assertThrows(GeneralSecurityException::class.java) { creator.pairing(transport).createOffer() }
        assertThrows(GeneralSecurityException::class.java) {
            creator.reconstructed().pairing(transport, clockAt(NOW.plusSeconds(1))).createOffer()
        }
        val recovered = creator.reconstructed().pairing(transport, clockAt(NOW.plusSeconds(2))).createOffer()

        assertEquals(3, attempts)
        assertEquals(OFFER_ID, RelayEnvelopeFormat.verifyPairing(recovered.toByteArray()).getString("offerId"))
    }

    // Mutations caught: using a different proof role/actor/transcript, sending client actor fields,
    // pinning server data instead of the QR, or creating an unsigned/unbound peer response QR.
    @Test
    fun acceptUsesExactTask5PeerProofPinsQrIdentityAndReturnsPeerSignedQr() {
        val creatorQr = createQr()
        val transport = RecordingTransport { path, body ->
            assertEquals("/v1/pairing/offers/$OFFER_ID/accept", path)
            assertJsonFields(body, "pairingSecret", "peerProof")
            assertEquals(PAIRING_SECRET, body.getString("pairingSecret"))
            assertEquals(PEER_PROOF, body.getString("peerProof"))
            response(200, acceptedBody(CREATOR_ID, PEER_ID))
        }

        val peerQr = peer.pairing(transport, clockAt(NOW.plusSeconds(1))).acceptOffer(creatorQr)

        assertEquals(1, transport.posts.size)
        assertTrue(RelayPeerStore(peer.context).isTrusted(CREATOR_ID, creator.identity))
        val verified = RelayEnvelopeFormat.verifyPairing(peerQr.toByteArray(Charsets.UTF_8))
        assertEquals(OFFER_ID, verified.getString("offerId"))
        assertEquals(PEER_ID, verified.getString("installId"))
        assertEquals(peer.identity.encryptionPublicKey, verified.getString("encryptionPublicKey"))
        assertEquals(peer.identity.signingPublicKey, verified.getString("signingPublicKey"))
        assertEquals(peer.identity.keyVersion, verified.getInt("keyVersion"))
        assertEquals(PAIRING_SECRET, verified.getString("pairingSecret"))
        assertEquals(EXPIRES_AT_TEXT, verified.getString("expiresAt"))
    }

    // Each entry catches omitting one signed trust-bearing field from the Task 10 signing input.
    @Test
    fun everyQrTrustFieldAndSignatureRejectsCanonicalTamperingBeforeNetworkOrPinning() {
        val validQr = createQr()
        val other = device("TEST-ONLY-other-identity")
        val original = JSONObject(validQr)
        val signature = Base64.getUrlDecoder().decode(original.getString("signature")).also {
            it[0] = (it[0].toInt() xor 1).toByte()
        }
        val mutations = linkedMapOf<String, Any>(
            "version" to 3,
            "offerId" to "offer-canonical-tamper-002",
            "installId" to "TEST-ONLY-substituted-creator",
            "encryptionPublicKey" to other.identity.encryptionPublicKey,
            "signingPublicKey" to other.identity.signingPublicKey,
            "keyVersion" to (creator.identity.keyVersion + 1),
            "pairingSecret" to Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 0x7f }),
            "expiresAt" to NOW.plusSeconds(TEN_MINUTES_SECONDS + 1).toString(),
            "signature" to Base64.getUrlEncoder().withoutPadding().encodeToString(signature)
        )

        mutations.forEach { (field, replacement) ->
            val scanner = device("TEST-ONLY-tamper-$field")
            val transport = RecordingTransport { _, _ -> throw AssertionError("Tampered QR reached HTTP") }
            val canonicalTamper = RelayEnvelopeFormat.canonical(JSONObject(validQr).put(field, replacement))

            assertThrows("$field tamper must fail", GeneralSecurityException::class.java) {
                scanner.pairing(transport, clockAt(NOW.plusSeconds(1))).acceptOffer(canonicalTamper)
            }

            assertTrue("$field tamper must not call HTTP", transport.posts.isEmpty())
            assertNull("$field tamper must not create trust", RelayPeerStore(scanner.context).pinnedIdentity(CREATOR_ID))
        }
    }

    // Mutations caught: <= instead of < at the expiry gate, second-rounded clocks, or a non-10m QR.
    @Test
    fun qrIsUsableOneMillisecondBeforeExpiryAndExpiredAtTheExactBoundary() {
        val qr = createQr()
        val before = device("TEST-ONLY-before-expiry")
        val beforeTransport = successfulAcceptTransport(before.installId)

        before.pairing(beforeTransport, clockAt(EXPIRES_AT.minusMillis(1))).acceptOffer(qr)

        assertEquals(1, beforeTransport.posts.size)
        assertTrue(RelayPeerStore(before.context).isTrusted(CREATOR_ID, creator.identity))

        val atBoundary = device("TEST-ONLY-at-expiry")
        val boundaryTransport = RecordingTransport { _, _ -> throw AssertionError("Expired QR reached HTTP") }

        assertThrows(GeneralSecurityException::class.java) {
            atBoundary.pairing(boundaryTransport, clockAt(EXPIRES_AT)).acceptOffer(qr)
        }
        assertTrue(boundaryTransport.posts.isEmpty())
        assertNull(RelayPeerStore(atBoundary.context).pinnedIdentity(CREATOR_ID))
    }

    // Mutations caught: accepting stale server state, accepting an unbounded server clock, or
    // accidentally requiring the server timestamp to equal the QR timestamp exactly.
    @Test
    fun serverExpiryMustBeFreshAndWithinTheDocumentedClockSkewWindow() {
        val responseExpiries = listOf(
            NOW.toEpochMilli(),
            NOW.plusSeconds(TEN_MINUTES_SECONDS + SERVER_EXPIRY_SKEW_SECONDS + 1).toEpochMilli()
        )
        responseExpiries.forEachIndexed { index, serverExpiry ->
            val scanner = device("TEST-ONLY-server-expiry-$index")
            val transport = RecordingTransport { _, _ ->
                response(200, acceptedBody(CREATOR_ID, scanner.installId, expiresAt = serverExpiry))
            }

            assertThrows(GeneralSecurityException::class.java) {
                scanner.pairing(transport).acceptOffer(createQr())
            }
            assertNull(RelayPeerStore(scanner.context).pinnedIdentity(CREATOR_ID))
        }

        val skewedButPlausible = device("TEST-ONLY-server-expiry-plausible")
        val plausibleTransport = RecordingTransport { _, _ -> response(
            200,
            acceptedBody(
                CREATOR_ID,
                skewedButPlausible.installId,
                expiresAt = NOW.plusSeconds(TEN_MINUTES_SECONDS + SERVER_EXPIRY_SKEW_SECONDS).toEpochMilli()
            )
        ) }
        skewedButPlausible.pairing(plausibleTransport).acceptOffer(createQr())
        assertTrue(RelayPeerStore(skewedButPlausible.context).isTrusted(CREATOR_ID, creator.identity))
    }

    // Mutation caught: applying timestamp bounds only to accept while allowing stale or implausibly
    // distant create responses to register durable secret-bearing offers.
    @Test
    fun createAlsoRejectsExpiredAndImplausiblyDistantServerExpiry() {
        var attempts = 0
        val transport = RecordingTransport { _, _ ->
            attempts++
            val expiresAt = if (attempts == 1) NOW.toEpochMilli() else
                NOW.plusSeconds(TEN_MINUTES_SECONDS + SERVER_EXPIRY_SKEW_SECONDS + 2).toEpochMilli()
            response(201, createdBody(CREATOR_ID, expiresAt = expiresAt))
        }

        assertThrows(GeneralSecurityException::class.java) { creator.pairing(transport).createOffer() }
        assertThrows(GeneralSecurityException::class.java) {
            creator.reconstructed().pairing(transport, clockAt(NOW.plusSeconds(1))).createOffer()
        }
        assertEquals(2, attempts)
    }

    // Mutations caught: process-memory replay tracking, marking only before restart, or allowing an
    // idempotent HTTP accept to reactivate a previously consumed signed QR.
    @Test
    fun acceptedOfferReplayIsRejectedAfterServiceStoreAndContextReconstruction() {
        val qr = createQr()
        val transport = successfulAcceptTransport(PEER_ID)

        peer.pairing(transport, clockAt(NOW.plusSeconds(1))).acceptOffer(qr)
        val restarted = peer.reconstructed().pairing(transport, clockAt(NOW.plusSeconds(2)))

        assertThrows(GeneralSecurityException::class.java) { restarted.acceptOffer(qr) }
        assertEquals("Replay must be rejected before a second HTTP request", 1, transport.posts.size)
        assertTrue(RelayPeerStore(peer.context).isTrusted(CREATOR_ID, creator.identity))
    }

    // Mutation caught: consuming before a response arrives or rejecting the exact accepted/confirmed
    // success schema returned by an idempotent retry after the server committed the first request.
    @Test
    fun acceptResponseLossRecoversFromAnExactConfirmedRetry() {
        val qr = createQr()
        var attempts = 0
        val transport = RecordingTransport { _, _ ->
            attempts++
            if (attempts == 1) throw java.io.IOException("synthetic response loss after server commit")
            response(200, acceptedBody(CREATOR_ID, PEER_ID, status = "confirmed"))
        }

        assertThrows(GeneralSecurityException::class.java) {
            peer.pairing(transport, clockAt(NOW.plusSeconds(1))).acceptOffer(qr)
        }
        assertNull(RelayPeerStore(peer.context).pinnedIdentity(CREATOR_ID))

        peer.reconstructed().pairing(transport, clockAt(NOW.plusSeconds(2))).acceptOffer(qr)

        assertEquals(2, attempts)
        assertTrue(RelayPeerStore(peer.context).isTrusted(CREATOR_ID, creator.identity))
    }

    // Mutations caught: retaining only accept-side replay state or forgetting the creator-side
    // consumed marker when the service/store is reconstructed after successful confirmation.
    @Test
    fun confirmedOfferReplayIsRejectedAfterServiceStoreAndContextReconstruction() {
        val creatorQr = createQr()
        val peerQr = peer.pairing(successfulAcceptTransport(PEER_ID), clockAt(NOW.plusSeconds(1)))
            .acceptOffer(creatorQr)
        val confirmTransport = RecordingTransport { _, _ -> response(200, confirmedBody()) }

        creator.pairing(confirmTransport, clockAt(NOW.plusSeconds(2))).confirmOffer(peerQr)
        val restarted = creator.reconstructed().pairing(confirmTransport, clockAt(NOW.plusSeconds(3)))

        assertThrows(GeneralSecurityException::class.java) { restarted.confirmOffer(peerQr) }
        assertEquals("Replay must be rejected before a second confirm request", 1, confirmTransport.posts.size)
        assertTrue(RelayPeerStore(creator.context).isTrusted(PEER_ID, peer.identity))
    }

    // Mutations caught: accepting server-returned public keys, ignoring unknown trust metadata, or
    // pinning before the accept response has been strictly matched to QR offer/actors.
    @Test
    fun serverKeySubstitutionIsRejectedAndCannotCreateOrReplaceQrTrust() {
        val qr = createQr()
        val impostor = device("TEST-ONLY-server-impostor")
        val body = acceptedBody(CREATOR_ID, PEER_ID)
            .put("encryptionPublicKey", impostor.identity.encryptionPublicKey)
            .put("signingPublicKey", impostor.identity.signingPublicKey)
            .put("keyVersion", impostor.identity.keyVersion)
        val transport = RecordingTransport { _, _ -> response(200, body) }

        assertThrows(GeneralSecurityException::class.java) {
            peer.pairing(transport, clockAt(NOW.plusSeconds(1))).acceptOffer(qr)
        }

        val restarted = RelayPeerStore(peer.context)
        assertNull(restarted.pinnedIdentity(CREATOR_ID))
        assertFalse(restarted.isTrusted(CREATOR_ID, impostor.identity))
        assertFalse(restarted.isTrusted(CREATOR_ID, creator.identity))
    }

    // Mutations caught: calling confirm before pin persistence, trusting the server as the peer-key
    // source, or treating an HTTP 200 as authority to pin after routes are already active.
    @Test
    fun creatorPinsIndependentlyVerifiedPeerQrBeforeConfirmCanActivateRoutes() {
        val creatorQr = createQr()
        val peerQr = peer.pairing(successfulAcceptTransport(PEER_ID), clockAt(NOW.plusSeconds(1)))
            .acceptOffer(creatorQr)
        val creatorPeers = RelayPeerStore(creator.context)
        assertNull(creatorPeers.pinnedIdentity(PEER_ID))
        var observedPinnedBeforeConfirm = false
        val confirmTransport = RecordingTransport { path, body ->
            assertEquals("/v1/pairing/offers/$OFFER_ID/confirm", path)
            assertJsonFields(body, "pairingSecret")
            assertEquals(PAIRING_SECRET, body.getString("pairingSecret"))
            observedPinnedBeforeConfirm = creatorPeers.isTrusted(PEER_ID, peer.identity)
            response(200, confirmedBody())
        }

        val paired = creator.pairing(confirmTransport, clockAt(NOW.plusSeconds(2))).confirmOffer(peerQr)

        assertTrue("Pin must be durable before confirm HTTP can activate routes", observedPinnedBeforeConfirm)
        assertEquals(PEER_ID, paired.installId)
        assertEquals(peer.identity, paired.identity)
        assertTrue(RelayPeerStore(creator.context).isTrusted(PEER_ID, peer.identity))
    }

    // Mutations caught: consuming/removing pending state on a rejected confirm, rolling back the
    // required pre-confirm pin, or preventing a safe retry after a server-side rejection.
    @Test
    fun rejectedConfirmKeepsTheAuthenticatedPinAndPendingOfferRetryable() {
        val creatorQr = createQr()
        val peerQr = peer.pairing(successfulAcceptTransport(PEER_ID), clockAt(NOW.plusSeconds(1)))
            .acceptOffer(creatorQr)
        var attempts = 0
        val transport = RecordingTransport { _, _ ->
            attempts++
            if (attempts == 1) response(409, JSONObject().put("error", "pairing_unavailable"))
            else response(200, confirmedBody())
        }

        assertThrows(GeneralSecurityException::class.java) {
            creator.pairing(transport, clockAt(NOW.plusSeconds(2))).confirmOffer(peerQr)
        }
        assertTrue(RelayPeerStore(creator.context).isTrusted(PEER_ID, peer.identity))

        val paired = creator.reconstructed().pairing(transport, clockAt(NOW.plusSeconds(3)))
            .confirmOffer(peerQr)

        assertEquals(2, attempts)
        assertEquals(PEER_ID, paired.installId)
        assertTrue(RelayPeerStore(creator.context).isTrusted(PEER_ID, peer.identity))
    }

    // Mutations caught: clearing the revoke fence before a rejected confirm, rolling the verified
    // candidate pin back to the old key, or clearing an unrelated peer's fence on retry success.
    @Test
    fun rejectedConfirmPersistsCandidateButKeepsRevokedPeerUnauthorizedUntilRetrySuccess() {
        val oldPeer = device("TEST-ONLY-rejected-confirm-old-peer")
        val unrelated = device("TEST-ONLY-rejected-confirm-unrelated")
        val store = RelayPeerStore(creator.context)
        assertTrue(store.pin(PEER_ID, oldPeer.identity))
        assertTrue(store.revoke(PEER_ID))
        assertTrue(store.pin(UNRELATED_ID, unrelated.identity))
        assertTrue(store.revoke(UNRELATED_ID))
        val creatorQr = createQr()
        val peerQr = peer.pairing(successfulAcceptTransport(PEER_ID), clockAt(NOW.plusSeconds(1)))
            .acceptOffer(creatorQr)
        var attempts = 0
        val transport = RecordingTransport { _, _ ->
            attempts++
            if (attempts == 1) response(409, JSONObject().put("error", "pairing_unavailable"))
            else response(200, confirmedBody())
        }

        assertThrows(GeneralSecurityException::class.java) {
            creator.pairing(transport, clockAt(NOW.plusSeconds(2))).confirmOffer(peerQr)
        }
        val afterRejected = RelayPeerStore(creator.context)
        assertEquals(peer.identity, afterRejected.pinnedIdentity(PEER_ID))
        assertTrue(afterRejected.isRevoked(PEER_ID))
        assertFalse(afterRejected.isTrusted(PEER_ID, peer.identity))
        assertTrue(afterRejected.isRevoked(UNRELATED_ID))

        creator.reconstructed().pairing(transport, clockAt(NOW.plusSeconds(3))).confirmOffer(peerQr)

        val recovered = RelayPeerStore(creator.context)
        assertEquals(2, attempts)
        assertFalse(recovered.isRevoked(PEER_ID))
        assertTrue(recovered.isTrusted(PEER_ID, peer.identity))
        assertTrue(recovered.isRevoked(UNRELATED_ID))
    }

    // Mutations caught: treating a thrown/lost confirm response as authorization, failing to stage
    // the candidate durably before HTTP, or rejecting an exact confirmed response on retry.
    @Test
    fun confirmResponseLossKeepsRevokeFenceThenExactRetryActivatesTheCandidate() {
        val oldPeer = device("TEST-ONLY-lost-confirm-old-peer")
        val unrelated = device("TEST-ONLY-lost-confirm-unrelated")
        val store = RelayPeerStore(creator.context)
        assertTrue(store.pin(PEER_ID, oldPeer.identity))
        assertTrue(store.revoke(PEER_ID))
        assertTrue(store.pin(UNRELATED_ID, unrelated.identity))
        assertTrue(store.revoke(UNRELATED_ID))
        val creatorQr = createQr()
        val peerQr = peer.pairing(successfulAcceptTransport(PEER_ID), clockAt(NOW.plusSeconds(1)))
            .acceptOffer(creatorQr)
        var attempts = 0
        val transport = RecordingTransport { _, _ ->
            attempts++
            if (attempts == 1) throw java.io.IOException("synthetic response loss after server commit")
            response(200, confirmedBody())
        }

        assertThrows(GeneralSecurityException::class.java) {
            creator.pairing(transport, clockAt(NOW.plusSeconds(2))).confirmOffer(peerQr)
        }
        val afterLoss = RelayPeerStore(creator.context)
        assertEquals(peer.identity, afterLoss.pinnedIdentity(PEER_ID))
        assertTrue(afterLoss.isRevoked(PEER_ID))
        assertFalse(afterLoss.isTrusted(PEER_ID, peer.identity))
        assertTrue(afterLoss.isRevoked(UNRELATED_ID))

        creator.reconstructed().pairing(transport, clockAt(NOW.plusSeconds(3))).confirmOffer(peerQr)

        assertEquals(2, attempts)
        assertTrue(RelayPeerStore(creator.context).isTrusted(PEER_ID, peer.identity))
        assertFalse(RelayPeerStore(creator.context).isRevoked(PEER_ID))
        assertTrue(RelayPeerStore(creator.context).isRevoked(UNRELATED_ID))
    }

    // Mutations caught: accepting one's own QR, trusting bearer-independent actor fields, or
    // confirming any validly signed peer offer instead of the locally pending offer/secret.
    @Test
    fun wrongActorAndWrongOfferFailClosedWithoutPairingHttpOrTrust() {
        val creatorQr = createQr()
        val noHttp = RecordingTransport { _, _ -> throw AssertionError("Invalid actor/offer reached HTTP") }

        assertThrows(GeneralSecurityException::class.java) {
            creator.pairing(noHttp, clockAt(NOW.plusSeconds(1))).acceptOffer(creatorQr)
        }
        assertTrue(noHttp.posts.isEmpty())

        val wrongActor = device("TEST-ONLY-wrong-response-peer")
        val wrongActorTransport = RecordingTransport { _, _ ->
            response(200, acceptedBody("TEST-ONLY-not-the-qr-creator", wrongActor.installId))
        }
        assertThrows(GeneralSecurityException::class.java) {
            wrongActor.pairing(wrongActorTransport, clockAt(NOW.plusSeconds(1))).acceptOffer(creatorQr)
        }
        assertNull(RelayPeerStore(wrongActor.context).pinnedIdentity(CREATOR_ID))

        val unrelatedOfferTransport = RecordingTransport { _, body ->
            response(201, createdBody(peer.installId, body.getString("offerId"), EXPIRES_AT.toEpochMilli()))
        }
        val unrelatedPeerQr = peer.pairing(
            unrelatedOfferTransport,
            randomness = ScriptedRandom(ALTERNATE_OFFER_BYTES, ALTERNATE_SECRET_BYTES)
        ).createOffer()
        val beforeConfirmCalls = noHttp.posts.size

        assertThrows(GeneralSecurityException::class.java) {
            creator.pairing(noHttp, clockAt(NOW.plusSeconds(2))).confirmOffer(unrelatedPeerQr)
        }
        assertEquals(beforeConfirmCalls, noHttp.posts.size)
        assertNull(RelayPeerStore(creator.context).pinnedIdentity(PEER_ID))
    }

    // Mutations caught: treating QR verification alone as completed re-pair, clearing revocation on
    // a rejected accept response, failing to replace the old pin, or clearing unrelated tombstones.
    @Test
    fun onlySuccessfulAuthenticatedAcceptAtomicallyRepairsTheMatchingRevokedCreator() {
        val oldCreator = device("TEST-ONLY-old-creator-identity")
        val unrelated = device("TEST-ONLY-accept-unrelated-identity")
        val peerStore = RelayPeerStore(peer.context)
        assertTrue(peerStore.pin(CREATOR_ID, oldCreator.identity))
        assertTrue(peerStore.revoke(CREATOR_ID))
        assertTrue(peerStore.pin(UNRELATED_ID, unrelated.identity))
        assertTrue(peerStore.revoke(UNRELATED_ID))
        val creatorQr = createQr()
        val rejectedTransport = RecordingTransport { _, _ ->
            response(409, JSONObject().put("error", "pairing_unavailable"))
        }

        assertThrows(Exception::class.java) {
            peer.pairing(rejectedTransport, clockAt(NOW.plusSeconds(1))).acceptOffer(creatorQr)
        }

        val afterFailure = RelayPeerStore(peer.context)
        assertEquals(oldCreator.identity, afterFailure.pinnedIdentity(CREATOR_ID))
        assertTrue(afterFailure.isRevoked(CREATOR_ID))
        assertTrue(afterFailure.isRevoked(UNRELATED_ID))

        peer.reconstructed().pairing(successfulAcceptTransport(PEER_ID), clockAt(NOW.plusSeconds(2)))
            .acceptOffer(creatorQr)

        val restarted = RelayPeerStore(peer.context)
        assertEquals(creator.identity, restarted.pinnedIdentity(CREATOR_ID))
        assertTrue(restarted.isTrusted(CREATOR_ID, creator.identity))
        assertFalse(restarted.isRevoked(CREATOR_ID))
        assertEquals(unrelated.identity, restarted.pinnedIdentity(UNRELATED_ID))
        assertTrue(restarted.isRevoked(UNRELATED_ID))
    }

    // Mutations caught: ordinary pin silently clearing revocation, replacing a pin without a fresh
    // signed matching flow, clearing every tombstone, or doing confirm before the atomic re-pair.
    @Test
    fun explicitAuthenticatedConfirmAtomicallyReplacesOnlyMatchingRevokedPeerPin() {
        val oldPeer = device("TEST-ONLY-old-peer-identity")
        val unrelated = device("TEST-ONLY-unrelated-identity")
        val peerStore = RelayPeerStore(creator.context)
        assertTrue(peerStore.pin(PEER_ID, oldPeer.identity))
        assertTrue(peerStore.revoke(PEER_ID))
        assertTrue(peerStore.pin(UNRELATED_ID, unrelated.identity))
        assertTrue(peerStore.revoke(UNRELATED_ID))
        assertFalse(peerStore.pin(PEER_ID, peer.identity))

        val creatorQr = createQr()
        val peerQr = peer.pairing(successfulAcceptTransport(PEER_ID), clockAt(NOW.plusSeconds(1)))
            .acceptOffer(creatorQr)
        var candidateFencedBeforeConfirm = false
        val confirmTransport = RecordingTransport { _, _ ->
            val durable = RelayPeerStore(creator.context)
            candidateFencedBeforeConfirm = durable.pinnedIdentity(PEER_ID) == peer.identity &&
                !durable.isTrusted(PEER_ID, peer.identity) && durable.isRevoked(PEER_ID) &&
                durable.isRevoked(UNRELATED_ID)
            response(200, confirmedBody())
        }

        creator.pairing(confirmTransport, clockAt(NOW.plusSeconds(2))).confirmOffer(peerQr)

        assertTrue(candidateFencedBeforeConfirm)
        val restarted = RelayPeerStore(creator.context)
        assertEquals(peer.identity, restarted.pinnedIdentity(PEER_ID))
        assertTrue(restarted.isTrusted(PEER_ID, peer.identity))
        assertFalse(restarted.isRevoked(PEER_ID))
        assertEquals(unrelated.identity, restarted.pinnedIdentity(UNRELATED_ID))
        assertTrue(restarted.isRevoked(UNRELATED_ID))
    }

    // Mutations caught: clearing a revoke tombstone on parse, on failed signature/offer matching,
    // or through the legacy/general pin API rather than explicit authenticated completion.
    @Test
    fun revokedPeerCannotSilentlyRepairFromInvalidOrUnmatchedQr() {
        val peerStore = RelayPeerStore(creator.context)
        assertTrue(peerStore.pin(PEER_ID, peer.identity))
        assertTrue(peerStore.revoke(PEER_ID))
        val creatorQr = createQr()
        val peerQr = peer.pairing(successfulAcceptTransport(PEER_ID), clockAt(NOW.plusSeconds(1)))
            .acceptOffer(creatorQr)
        val tampered = RelayEnvelopeFormat.canonical(
            JSONObject(peerQr).put("offerId", "offer-authenticated-but-unmatched")
        )
        val noHttp = RecordingTransport { _, _ -> throw AssertionError("Invalid re-pair reached HTTP") }

        assertThrows(GeneralSecurityException::class.java) {
            creator.pairing(noHttp, clockAt(NOW.plusSeconds(2))).confirmOffer(tampered)
        }

        val restarted = RelayPeerStore(creator.context)
        assertTrue(restarted.isRevoked(PEER_ID))
        assertFalse(restarted.isTrusted(PEER_ID, peer.identity))
        assertEquals(peer.identity, restarted.pinnedIdentity(PEER_ID))
        assertTrue(noHttp.posts.isEmpty())
    }

    // Mutations caught: retaining secret-bearing expired pending QR state or expired replay markers
    // forever instead of cleaning them opportunistically on any later pairing operation.
    @Test
    fun serviceOperationsCleanExpiredPendingSecretsAndConsumedMarkers() {
        val creatorQr = createQr()
        peer.pairing(successfulAcceptTransport(PEER_ID), clockAt(NOW.plusSeconds(1)))
            .acceptOffer(creatorQr)
        val creatorPrefs = creator.context.getSharedPreferences("dosefolk_relay_peers", Context.MODE_PRIVATE)
        val peerPrefs = peer.context.getSharedPreferences("dosefolk_relay_peers", Context.MODE_PRIVATE)
        assertTrue(creatorPrefs.contains("pairing:pending-offer-v2"))
        assertTrue(peerPrefs.all.keys.any { it.startsWith("pairing:consumed-v2:") })

        val noHttp = RecordingTransport { _, _ -> throw AssertionError("Invalid QR reached HTTP") }
        assertThrows(GeneralSecurityException::class.java) {
            creator.reconstructed().pairing(noHttp, clockAt(EXPIRES_AT)).acceptOffer("not-a-pairing-qr")
        }
        assertThrows(GeneralSecurityException::class.java) {
            peer.reconstructed().pairing(noHttp, clockAt(EXPIRES_AT)).acceptOffer("not-a-pairing-qr")
        }

        assertFalse(creatorPrefs.contains("pairing:pending-offer-v2"))
        assertFalse(peerPrefs.all.keys.any { it.startsWith("pairing:consumed-v2:") })
        assertFalse(creatorPrefs.all.values.any { it.toString().contains(PAIRING_SECRET) })
        assertTrue(noHttp.posts.isEmpty())
    }

    // Mutations caught: including secrets/keys or hostile server detail in exception messages,
    // println/logcat diagnostics, request/result toString(), or transport failure reporting.
    @Test
    fun pairingDiagnosticsNeverLogCredentialSecretKeysCiphertextOrDomainData() {
        val credential = "TEST-ONLY-BEARER-CREDENTIAL-never-log"
        val ciphertext = "TEST-ONLY-CIPHERTEXT-never-log"
        val domainData = "TEST-ONLY-MEDICATION-never-log"
        val transport = RecordingTransport(credential) { _, _ -> response(409, JSONObject()
            .put("error", "pairing_unavailable")
            .put("hostileSecret", PAIRING_SECRET)
            .put("hostileKey", creator.identity.signingPublicKey)
            .put("hostileCiphertext", ciphertext)
            .put("hostileDomainData", domainData)) }
        val captured = ByteArrayOutputStream()
        val previousOut = System.out
        val previousErr = System.err
        val stream = PrintStream(captured, true, "UTF-8")
        ShadowLog.clear()
        var diagnostic = ""
        try {
            System.setOut(stream)
            System.setErr(stream)
            val rejected = assertThrows(Exception::class.java) { creator.pairing(transport).createOffer() }
            diagnostic = listOf(rejected, creator.pairing(transport)).joinToString("\n") + "\n" +
                ShadowLog.getLogs().joinToString("\n") { "${it.msg} ${it.throwable}" }
        } finally {
            System.setOut(previousOut)
            System.setErr(previousErr)
            stream.close()
        }

        val observed = diagnostic + captured.toString("UTF-8")
        listOf(credential, PAIRING_SECRET, creator.identity.encryptionPublicKey,
            creator.identity.signingPublicKey, ciphertext, domainData).forEach { sensitive ->
            assertFalse("Pairing diagnostics leaked sensitive input", observed.contains(sensitive))
        }
    }

    // Regression guard: relay v2 is additive until cutover; current ntfy Circle QR parsing remains live.
    @Test
    fun legacyCircleQrAndRawTopicParsingRemainUnchanged() {
        val canonical = "dosefolk://pair?topic=dosefolk-legacy-peer&name=Legacy%20Peer"
        assertEquals(
            CirclePairingPayload("dosefolk-legacy-peer", "Legacy Peer"),
            CirclePairingPayload.parse(canonical)
        )
        assertEquals(canonical, CirclePairingPayload.encode("dosefolk-legacy-peer", "Legacy Peer"))
        assertEquals(
            CirclePairingPayload("dosefolk-legacy-raw", ""),
            CirclePairingPayload.parse("dosefolk-legacy-raw")
        )
    }

    private fun createQr(): String {
        val transport = RecordingTransport { _, body ->
            assertEquals(OFFER_ID, body.getString("offerId"))
            response(201, createdBody(CREATOR_ID))
        }
        return creator.pairing(transport).createOffer()
    }

    private fun successfulAcceptTransport(peerInstallId: String) = RecordingTransport { _, _ ->
        response(200, acceptedBody(CREATOR_ID, peerInstallId))
    }

    private fun createdBody(
        creatorInstallId: String,
        offerId: String = OFFER_ID,
        expiresAt: Long = EXPIRES_AT.toEpochMilli()
    ) = JSONObject().put("offerId", offerId).put("creatorInstallId", creatorInstallId)
        .put("expiresAt", expiresAt).put("status", "pending")

    private fun acceptedBody(
        creatorInstallId: String,
        peerInstallId: String,
        expiresAt: Long = EXPIRES_AT.toEpochMilli(),
        status: String = "accepted"
    ) = JSONObject()
        .put("offerId", OFFER_ID).put("creatorInstallId", creatorInstallId)
        .put("peerInstallId", peerInstallId).put("expiresAt", expiresAt)
        .put("status", status)

    private fun confirmedBody() = JSONObject().put("offerId", OFFER_ID).put("status", "confirmed")

    private fun response(status: Int, body: JSONObject) = RelayPairingHttpResponse(status, body)

    private fun assertJsonFields(body: JSONObject, vararg fields: String) {
        assertEquals(fields.toSet(), body.keys().asSequence().toSet())
    }

    private fun clockAt(instant: Instant) = Clock.fixed(instant, ZoneOffset.UTC)

    private fun device(installId: String): TestDevice {
        val context = contextFor(installId)
        val master = TestMasterAeadSource()
        val identityStore = RelayIdentityStore(context, master)
        val identity = identityStore.publicIdentity()
        return TestDevice(installId, context, master, identity)
    }

    private fun contextFor(storageId: String): Context = object : ContextWrapper(application) {
        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("$namespace-$storageId-$name", mode)
    }

    private inner class TestDevice(
        val installId: String,
        val context: Context,
        private val master: TestMasterAeadSource,
        val identity: RelayPublicIdentity
    ) {
        fun pairing(
            transport: RelayPairingTransport,
            clock: Clock = clockAt(NOW),
            randomness: RelayPairingRandom = ScriptedRandom(OFFER_BYTES, PAIRING_SECRET_BYTES)
        ) = RelayPairing(
            context = context,
            localInstallId = installId,
            identityStore = RelayIdentityStore(context, master),
            peerStore = RelayPeerStore(context),
            transport = transport,
            clock = clock,
            randomness = randomness
        )

        fun reconstructed() = TestDevice(
            installId,
            contextFor(installId),
            master,
            RelayIdentityStore(context, master).publicIdentity()
        )
    }

    private class TestMasterAeadSource : RelayMasterAeadSource {
        private val master = KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM)
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
        private var created = false

        override fun exists(): Boolean = created

        override fun createNew(): Aead {
            check(!created) { "TEST-ONLY master must not be replaced" }
            created = true
            return master
        }

        override fun getExisting(): Aead {
            check(created) { "TEST-ONLY master does not exist" }
            return master
        }
    }

    private class ScriptedRandom(vararg values: ByteArray) : RelayPairingRandom {
        private val remaining = ArrayDeque(values.map { it.clone() })

        override fun bytes(count: Int): ByteArray {
            val next = remaining.removeFirstOrNull() ?: throw AssertionError("Unexpected randomness request")
            assertEquals("Randomness byte count", count, next.size)
            return next.clone()
        }
    }

    private class RecordingTransport(
        @Suppress("unused") private val bearerCredential: String? = null,
        private val responder: (String, JSONObject) -> RelayPairingHttpResponse
    ) : RelayPairingTransport {
        constructor(responder: (String, JSONObject) -> RelayPairingHttpResponse) : this(null, responder)

        val posts = mutableListOf<Pair<String, JSONObject>>()

        override fun post(path: String, body: JSONObject): RelayPairingHttpResponse {
            posts += path to JSONObject(body.toString())
            return responder(path, JSONObject(body.toString()))
        }

        override fun toString(): String = "RecordingTransport(redacted)"
    }

    companion object {
        private const val CREATOR_ID = "TEST-ONLY-creator"
        private const val PEER_ID = "TEST-ONLY-peer"
        private const val UNRELATED_ID = "TEST-ONLY-unrelated"
        private const val TEN_MINUTES_SECONDS = 600L
        private const val SERVER_EXPIRY_SKEW_SECONDS = 120L
        private val NOW: Instant = Instant.parse("2030-01-01T00:00:00Z")
        private val EXPIRES_AT: Instant = NOW.plusSeconds(TEN_MINUTES_SECONDS)
        private const val EXPIRES_AT_TEXT = "2030-01-01T00:10:00Z"

        private val OFFER_BYTES = ByteArray(18) { (0xa0 + it).toByte() }
        private val PAIRING_SECRET_BYTES = ByteArray(32) { it.toByte() }
        private val ALTERNATE_OFFER_BYTES = ByteArray(18) { (0xc0 + it).toByte() }
        private val ALTERNATE_SECRET_BYTES = ByteArray(32) { (0x40 + it).toByte() }

        private const val OFFER_ID = "offer-oKGio6SlpqeoqaqrrK2ur7Cx"
        private const val PAIRING_SECRET = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
        private const val SECRET_HASH = "6oZqdX5MOLq_qBJ8vppAnT4fk6AP8UiP9zX8-Rev_9A"
        private const val CREATOR_PROOF = "PPzyIBKFMmzcT6k-9eGDZrjpILOwryykTfG89kG5heA"
        private const val PEER_PROOF = "CnW818obuiHA5T10iYc5S5u2nvCLTXtFL_OWRmAkoUU"
    }
}
