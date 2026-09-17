package com.ozkanmut.ilactakip

import android.content.Context
import org.json.JSONObject
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Already-authenticated HTTP boundary. Credential ownership remains outside pairing code. */
internal fun interface RelayPairingTransport {
    fun post(path: String, body: JSONObject): RelayPairingHttpResponse
}

/** Response diagnostics are deliberately redacted because pairing bodies can contain secrets. */
internal class RelayPairingHttpResponse(val status: Int, val body: JSONObject) {
    override fun toString(): String = "RelayPairingHttpResponse(redacted)"
}

internal fun interface RelayPairingRandom {
    fun bytes(count: Int): ByteArray
}

internal data class RelayPairedPeer(val installId: String, val identity: RelayPublicIdentity) {
    override fun toString(): String = "RelayPairedPeer(redacted)"
}

/**
 * Authenticated relay pairing-v2 state machine. It does not select a URL, own credentials, log,
 * touch the legacy UI, or activate a transport itself. Call off the UI thread: HTTP and durable
 * SharedPreferences commits are synchronous through the injected boundaries.
 */
internal class RelayPairing(
    context: Context,
    private val localInstallId: String,
    private val identityStore: RelayIdentityStore,
    private val peerStore: RelayPeerStore,
    private val transport: RelayPairingTransport,
    private val clock: Clock,
    private val randomness: RelayPairingRandom
) {
    private val preferences = context.applicationContext
        .getSharedPreferences(RELAY_PREFERENCES, Context.MODE_PRIVATE)

    fun createOffer(): String = safely {
        synchronized(pairingLock) {
            val now = clock.instant()
            cleanupExpiredState(now)
            val existing = readPendingOffer()
            val pending = if (existing == null || !now.isBefore(existing.payload.expiresAt)) {
                if (existing != null) removePendingOffer()
                newPendingOffer(now)
            } else {
                requirePairing(existing.payload.installId == localInstallId)
                requirePairing(existing.payload.identity == identityStore.publicIdentity())
                existing
            }

            if (!pending.serverRegistered) {
                val response = transport.post(
                    "/v1/pairing/offers",
                    JSONObject()
                        .put("offerId", pending.payload.offerId)
                        .put("secretHash", secretHash(pending.payload.pairingSecret))
                        .put(
                            "creatorProof",
                            pairingProof(
                                pending.payload.pairingSecret,
                                pending.payload.offerId,
                                ROLE_CREATOR,
                                localInstallId
                            )
                        )
                )
                requireCreateResponse(response, pending.payload, clock.instant())
                writePendingOffer(pending.copy(serverRegistered = true))
            }
            pending.qr
        }
    }

    fun acceptOffer(creatorQr: String): String = safely {
        synchronized(pairingLock) {
            val now = clock.instant()
            cleanupExpiredState(now)
            val creator = verifiedActiveQr(creatorQr, now)
            requirePairing(creator.installId != localInstallId)
            val consumedKey = consumedKey(ROLE_PEER, creator.offerId, creator.installId)
            requirePairing(!preferences.contains(consumedKey))

            // Finish every local cryptographic operation before the server changes offer state.
            val localIdentity = identityStore.publicIdentity()
            val peerPayload = RelayCirclePairingPayload(
                offerId = creator.offerId,
                installId = localInstallId,
                identity = localIdentity,
                pairingSecret = creator.pairingSecret,
                expiresAt = creator.expiresAt
            )
            val peerQr = CirclePairingPayload.encodeRelayV2(peerPayload, identityStore)
            val response = transport.post(
                "/v1/pairing/offers/${creator.offerId}/accept",
                JSONObject()
                    .put("pairingSecret", creator.pairingSecret)
                    .put(
                        "peerProof",
                        pairingProof(
                            creator.pairingSecret,
                            creator.offerId,
                            ROLE_PEER,
                            localInstallId
                        )
                    )
            )
            val responseNow = clock.instant()
            requireAcceptResponse(response, creator, responseNow)
            requirePairing(responseNow.isBefore(creator.expiresAt))
            requirePairing(PairingLifecycle.completeAuthenticatedRelayRePair(
                peerStore, creator.installId, creator.identity
            ))
            markConsumed(consumedKey, creator.expiresAt)
            peerQr
        }
    }

    fun confirmOffer(peerQr: String): RelayPairedPeer = safely {
        synchronized(pairingLock) {
            val now = clock.instant()
            cleanupExpiredState(now)
            val peer = verifiedActiveQr(peerQr, now)
            requirePairing(peer.installId != localInstallId)
            val pending = readPendingOffer() ?: rejectPairing()
            requirePairing(pending.serverRegistered)
            requirePairing(now.isBefore(pending.payload.expiresAt))
            requirePairing(pending.payload.installId == localInstallId)
            requirePairing(pending.payload.identity == identityStore.publicIdentity())
            requirePairing(peer.offerId == pending.payload.offerId)
            requirePairing(peer.pairingSecret == pending.payload.pairingSecret)
            requirePairing(peer.expiresAt == pending.payload.expiresAt)
            val consumedKey = consumedKey(ROLE_CREATOR, peer.offerId, peer.installId)
            requirePairing(!preferences.contains(consumedKey))

            // The server may activate routes during this call, so the authenticated pin must be
            // durable before transport sees the confirm request.
            requirePairing(PairingLifecycle.stageAuthenticatedRelayRePairCandidate(
                peerStore, peer.installId, peer.identity
            ))
            val response = transport.post(
                "/v1/pairing/offers/${peer.offerId}/confirm",
                JSONObject().put("pairingSecret", peer.pairingSecret)
            )
            requireConfirmResponse(response, peer.offerId)
            requirePairing(PairingLifecycle.activateAuthenticatedRelayRePairCandidate(
                peerStore, peer.installId, peer.identity
            ))
            finishConfirmedOffer(consumedKey, peer.expiresAt)
            RelayPairedPeer(peer.installId, peer.identity)
        }
    }

    override fun toString(): String = "RelayPairing(redacted)"

    private fun newPendingOffer(now: Instant): PendingOffer {
        RelayEnvelopeFormat.requireOpaqueId(localInstallId)
        val offerBytes = randomness.bytes(OFFER_ID_BYTES)
        val secretBytes = randomness.bytes(PAIRING_SECRET_BYTES)
        requirePairing(offerBytes.size == OFFER_ID_BYTES && secretBytes.size == PAIRING_SECRET_BYTES)
        val payload = RelayCirclePairingPayload(
            offerId = "offer-${encode(offerBytes)}",
            installId = localInstallId,
            identity = identityStore.publicIdentity(),
            pairingSecret = encode(secretBytes),
            expiresAt = now.truncatedTo(ChronoUnit.SECONDS).plusSeconds(PAIRING_LIFETIME_SECONDS)
        )
        val pending = PendingOffer(
            qr = CirclePairingPayload.encodeRelayV2(payload, identityStore),
            payload = payload,
            serverRegistered = false
        )
        writePendingOffer(pending)
        return pending
    }

    private fun verifiedActiveQr(raw: String, now: Instant): RelayCirclePairingPayload =
        CirclePairingPayload.parseRelayV2(raw).also {
            requirePairing(now.isBefore(it.expiresAt))
        }

    private fun cleanupExpiredState(now: Instant) {
        val staleKeys = mutableSetOf<String>()
        preferences.all.forEach { (key, value) ->
            when {
                key == PENDING_OFFER -> {
                    val expiresAt = runCatching {
                        val record = JSONObject(value as String)
                        RelayEnvelopeFormat.requireFields(record, setOf("qr", "serverRegistered"))
                        CirclePairingPayload.parseRelayV2(record.getString("qr")).expiresAt
                    }.getOrNull()
                    if (expiresAt == null || !now.isBefore(expiresAt)) staleKeys += key
                }
                key.startsWith(CONSUMED_PREFIX) && value is Long && value <= now.toEpochMilli() -> {
                    staleKeys += key
                }
            }
        }
        if (staleKeys.isEmpty()) return
        val editor = preferences.edit()
        staleKeys.forEach { editor.remove(it) }
        requirePairing(editor.commit())
    }

    private fun writePendingOffer(pending: PendingOffer) {
        val record = JSONObject()
            .put("qr", pending.qr)
            .put("serverRegistered", pending.serverRegistered)
        val previous = preferences.getString(PENDING_OFFER, null)
        val committed = preferences.edit().putString(PENDING_OFFER, record.toString()).commit()
        if (!committed) restoreString(PENDING_OFFER, previous)
        requirePairing(committed)
    }

    private fun readPendingOffer(): PendingOffer? {
        if (!preferences.contains(PENDING_OFFER)) return null
        val encoded = preferences.getString(PENDING_OFFER, null) ?: rejectPairing()
        val record = JSONObject(encoded)
        RelayEnvelopeFormat.requireFields(record, setOf("qr", "serverRegistered"))
        val qr = record.opt("qr") as? String ?: rejectPairing()
        val registered = record.opt("serverRegistered") as? Boolean ?: rejectPairing()
        return PendingOffer(qr, CirclePairingPayload.parseRelayV2(qr), registered)
    }

    private fun removePendingOffer() {
        val previous = preferences.getString(PENDING_OFFER, null) ?: rejectPairing()
        val committed = preferences.edit().remove(PENDING_OFFER).commit()
        if (!committed) restoreString(PENDING_OFFER, previous)
        requirePairing(committed)
    }

    private fun markConsumed(key: String, expiresAt: Instant) {
        val committed = preferences.edit().putLong(key, expiresAt.toEpochMilli()).commit()
        if (!committed) preferences.edit().remove(key).commit()
        requirePairing(committed)
    }

    private fun finishConfirmedOffer(consumedKey: String, expiresAt: Instant) {
        val pending = preferences.getString(PENDING_OFFER, null) ?: rejectPairing()
        val committed = preferences.edit()
            .putLong(consumedKey, expiresAt.toEpochMilli())
            .remove(PENDING_OFFER)
            .commit()
        if (!committed) {
            preferences.edit().remove(consumedKey).putString(PENDING_OFFER, pending).commit()
        }
        requirePairing(committed)
    }

    private fun restoreString(key: String, previous: String?) {
        val restore = preferences.edit()
        if (previous == null) restore.remove(key) else restore.putString(key, previous)
        restore.commit()
    }

    private fun requireCreateResponse(
        response: RelayPairingHttpResponse,
        offer: RelayCirclePairingPayload,
        now: Instant
    ) {
        requirePairing(response.status == 201)
        RelayEnvelopeFormat.requireFields(
            response.body, setOf("offerId", "creatorInstallId", "expiresAt", "status")
        )
        requirePairing(response.body.opt("offerId") == offer.offerId)
        requirePairing(response.body.opt("creatorInstallId") == localInstallId)
        requirePairing(response.body.opt("status") == "pending")
        requireServerExpiry(response.body, now)
    }

    private fun requireAcceptResponse(
        response: RelayPairingHttpResponse,
        creator: RelayCirclePairingPayload,
        now: Instant
    ) {
        requirePairing(response.status == 200)
        RelayEnvelopeFormat.requireFields(
            response.body,
            setOf("offerId", "creatorInstallId", "peerInstallId", "expiresAt", "status")
        )
        requirePairing(response.body.opt("offerId") == creator.offerId)
        requirePairing(response.body.opt("creatorInstallId") == creator.installId)
        requirePairing(response.body.opt("peerInstallId") == localInstallId)
        requirePairing(response.body.opt("status") in setOf("accepted", "confirmed"))
        requireServerExpiry(response.body, now)
    }

    private fun requireConfirmResponse(response: RelayPairingHttpResponse, offerId: String) {
        requirePairing(response.status == 200)
        RelayEnvelopeFormat.requireFields(response.body, setOf("offerId", "status"))
        requirePairing(response.body.opt("offerId") == offerId)
        requirePairing(response.body.opt("status") == "confirmed")
    }

    private fun positiveLong(json: JSONObject, name: String): Long = when (val value = json.opt(name)) {
        is Int -> value.toLong()
        is Long -> value
        else -> rejectPairing()
    }

    private fun requireServerExpiry(json: JSONObject, now: Instant) {
        val expiresAt = Instant.ofEpochMilli(positiveLong(json, "expiresAt"))
        requirePairing(expiresAt.isAfter(now))
        requirePairing(!expiresAt.isAfter(now.plusSeconds(MAX_SERVER_EXPIRY_SECONDS)))
    }

    private fun consumedKey(role: String, offerId: String, installId: String): String {
        val opaque = "$role\u0000$offerId\u0000$installId".toByteArray(Charsets.UTF_8)
        return "$CONSUMED_PREFIX${encode(MessageDigest.getInstance("SHA-256").digest(opaque))}"
    }

    private fun secretHash(pairingSecret: String): String = encode(
        MessageDigest.getInstance("SHA-256").digest(pairingSecret.toByteArray(Charsets.UTF_8))
    )

    private fun pairingProof(secret: String, offerId: String, role: String, installId: String): String {
        val transcript = "dosefolk-pairing-v2\n$role\n$offerId\n$installId".toByteArray(Charsets.UTF_8)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return encode(mac.doFinal(transcript))
    }

    private fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private inline fun <T> safely(action: () -> T): T = try {
        action()
    } catch (_: Exception) {
        rejectPairing()
    }

    private data class PendingOffer(
        val qr: String,
        val payload: RelayCirclePairingPayload,
        val serverRegistered: Boolean
    ) {
        override fun toString(): String = "PendingOffer(redacted)"
    }

    private companion object {
        const val RELAY_PREFERENCES = "dosefolk_relay_peers"
        const val PENDING_OFFER = "pairing:pending-offer-v2"
        const val CONSUMED_PREFIX = "pairing:consumed-v2:"
        const val ROLE_CREATOR = "creator"
        const val ROLE_PEER = "peer"
        const val OFFER_ID_BYTES = 18
        const val PAIRING_SECRET_BYTES = 32
        const val PAIRING_LIFETIME_SECONDS = 10L * 60L
        // Relay expiry uses its own clock; allow at most two minutes of forward skew/response delay.
        const val SERVER_EXPIRY_SKEW_SECONDS = 2L * 60L
        const val MAX_SERVER_EXPIRY_SECONDS = PAIRING_LIFETIME_SECONDS + SERVER_EXPIRY_SKEW_SECONDS
        val pairingLock = Any()
    }
}

private fun requirePairing(condition: Boolean) {
    if (!condition) rejectPairing()
}

private fun rejectPairing(): Nothing = throw GeneralSecurityException("Invalid relay pairing")
