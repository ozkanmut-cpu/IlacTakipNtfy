package com.ozkanmut.ilactakip

import android.net.Uri
import org.json.JSONObject
import java.time.Instant

data class CirclePairingPayload(val topic: String, val name: String) {
    companion object {
        fun encode(topic: String, name: String): String = Uri.Builder()
            .scheme("dosefolk")
            .authority("pair")
            .appendQueryParameter("topic", topic)
            .appendQueryParameter("name", name)
            .build()
            .toString()

        fun parse(raw: String): CirclePairingPayload? {
            val value = raw.trim()
            if (value.startsWith("dosefolk://pair")) {
                val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return null
                if (uri.scheme != "dosefolk" || uri.authority != "pair") return null
                val topic = uri.getQueryParameter("topic").orEmpty().trim()
                val name = uri.getQueryParameter("name").orEmpty().trim()
                if (topic.isBlank()) return null
                return CirclePairingPayload(topic, name)
            }
            if (value.startsWith("dosefolk-") && value.length >= 12) {
                return CirclePairingPayload(value, "")
            }
            return null
        }

        /** Authenticated relay-v2 QR codec; the legacy parser above remains unchanged until cutover. */
        internal fun encodeRelayV2(
            payload: RelayCirclePairingPayload,
            identityStore: RelayIdentityStore
        ): String {
            RelayEnvelopeFormat.requireOpaqueId(payload.offerId)
            RelayEnvelopeFormat.requireOpaqueId(payload.installId)
            requireRelay(PAIRING_ID.matches(payload.offerId))
            requireRelay(PAIRING_ID.matches(payload.installId))
            requireRelay(payload.identity.isValid())
            requireRelay(identityStore.publicIdentity() == payload.identity)
            RelayEnvelopeFormat.decode(payload.pairingSecret, 32)
            requireRelay(payload.expiresAt.nano == 0)
            val expiry = payload.expiresAt.toString()
            requireRelay(expiry.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z")))
            val unsigned = JSONObject()
                .put("version", 2)
                .put("offerId", payload.offerId)
                .put("installId", payload.installId)
                .put("encryptionPublicKey", payload.identity.encryptionPublicKey)
                .put("signingPublicKey", payload.identity.signingPublicKey)
                .put("keyVersion", payload.identity.keyVersion)
                .put("pairingSecret", payload.pairingSecret)
                .put("expiresAt", expiry)
            val signature = identityStore.signRelay(
                RelayEnvelopeFormat.pairingSigningInput(unsigned), payload.identity.keyVersion
            )
            return RelayEnvelopeFormat.canonical(unsigned.put("signature", RelayEnvelopeFormat.encode(signature)))
        }

        internal fun parseRelayV2(raw: String): RelayCirclePairingPayload {
            val verified = RelayEnvelopeFormat.verifyPairing(raw.toByteArray(Charsets.UTF_8))
            val offerId = verified.getString("offerId")
            val installId = verified.getString("installId")
            requireRelay(PAIRING_ID.matches(offerId))
            requireRelay(PAIRING_ID.matches(installId))
            val encryptionPublicKey = verified.getString("encryptionPublicKey")
            val signingPublicKey = verified.getString("signingPublicKey")
            val keyVersion = verified.getInt("keyVersion")
            val identity = RelayPublicIdentity(
                encryptionPublicKey = encryptionPublicKey,
                signingPublicKey = signingPublicKey,
                keyVersion = keyVersion,
                fingerprint = RelayPublicIdentity.fingerprintFor(encryptionPublicKey, signingPublicKey, keyVersion)
            )
            requireRelay(identity.isValid())
            return RelayCirclePairingPayload(
                offerId = offerId,
                installId = installId,
                identity = identity,
                pairingSecret = verified.getString("pairingSecret"),
                expiresAt = Instant.parse(verified.getString("expiresAt"))
            )
        }
        private val PAIRING_ID = Regex("[A-Za-z0-9._-]{8,128}")
    }
}

/** Secret-bearing verified QR state stays internal and redacts diagnostics. */
internal data class RelayCirclePairingPayload(
    val offerId: String,
    val installId: String,
    val identity: RelayPublicIdentity,
    val pairingSecret: String,
    val expiresAt: Instant
) {
    override fun toString(): String = "RelayCirclePairingPayload(redacted)"
}
