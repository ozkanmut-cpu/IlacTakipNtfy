package com.ozkanmut.ilactakip

import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.PublicKeyVerify
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.hybrid.HpkeParameters
import com.google.crypto.tink.hybrid.HpkePublicKey
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.signature.Ed25519Parameters
import com.google.crypto.tink.signature.Ed25519PublicKey
import com.google.crypto.tink.signature.SignatureConfig
import com.google.crypto.tink.util.Bytes
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.GeneralSecurityException
import java.util.Base64

/** Routing metadata is authenticated twice: by HPKE contextInfo and the signed inner envelope. */
data class RelayOuterContext(
    val messageId: String,
    val routeId: String,
    val senderInstallId: String,
    val recipientInstallId: String,
    val senderKeyVersion: Int,
    val recipientKeyVersion: Int
) {
    internal fun json(): JSONObject {
        listOf(messageId, routeId, senderInstallId, recipientInstallId).forEach(RelayEnvelopeFormat::requireOpaqueId)
        requireRelay(senderKeyVersion > 0 && recipientKeyVersion > 0)
        return JSONObject().put("messageId", messageId).put("routeId", routeId)
            .put("senderInstallId", senderInstallId).put("recipientInstallId", recipientInstallId)
            .put("senderKeyVersion", senderKeyVersion).put("recipientKeyVersion", recipientKeyVersion)
    }
}

/** Constructed only after both authentication layers pass. Diagnostics never include the payload. */
class VerifiedPayload internal constructor(val domainPayload: JSONObject) {
    override fun toString(): String = "VerifiedPayload(redacted)"
}

/** Device-local codec. Call off the UI thread; identity capabilities reopen encrypted disk keysets. */
class RelayCrypto(private val identityStore: RelayIdentityStore, private val localInstallId: String) {
    /** Public version only; private keysets remain confined to RelayIdentityStore. */
    internal fun localKeyVersion(): Int = identityStore.publicIdentity().keyVersion

    fun seal(domainPayload: JSONObject, recipient: RelayPublicIdentity, outerContext: RelayOuterContext): ByteArray = safely {
        HybridConfig.register()
        val context = outerContext.json()
        requireRelay(localInstallId == outerContext.senderInstallId && recipient.isValid())
        requireRelay(identityStore.publicIdentity().keyVersion == outerContext.senderKeyVersion)
        requireRelay(recipient.keyVersion == outerContext.recipientKeyVersion)
        // Snapshot/validate caller-owned mutable JSON before signing, then sign exactly the snapshot.
        val payload = RelayEnvelopeFormat.parseCanonical(RelayEnvelopeFormat.canonical(domainPayload).toByteArray(Charsets.UTF_8))
        val unsigned = JSONObject().put("context", context).put("payload", payload).put("version", 1)
        val signature = identityStore.signRelay(RelayEnvelopeFormat.signingInput(unsigned), outerContext.senderKeyVersion)
        val inner = RelayEnvelopeFormat.canonical(unsigned.put("signature", RelayEnvelopeFormat.encode(signature)))
            .toByteArray(Charsets.UTF_8)
        requireRelay(inner.size <= RelayEnvelopeFormat.MAX_BYTES - 48)
        val parameters = HpkeParameters.builder()
            .setKemId(HpkeParameters.KemId.DHKEM_X25519_HKDF_SHA256)
            .setKdfId(HpkeParameters.KdfId.HKDF_SHA256)
            .setAeadId(HpkeParameters.AeadId.CHACHA20_POLY1305)
            .setVariant(HpkeParameters.Variant.NO_PREFIX).build()
        val key = HpkePublicKey.create(parameters,
            Bytes.copyFrom(RelayEnvelopeFormat.decode(recipient.encryptionPublicKey, 32)), null)
        val handle = KeysetHandle.newBuilder().addEntry(KeysetHandle.importKey(key).withFixedId(1).makePrimary()).build()
        handle.getPrimitive(RegistryConfiguration.get(), HybridEncrypt::class.java)
            .encrypt(inner, RelayEnvelopeFormat.hpkeContext(context))
    }

    fun open(ciphertext: ByteArray, sender: RelayPublicIdentity, outerContext: RelayOuterContext): VerifiedPayload = safely {
        val context = outerContext.json()
        requireRelay(ciphertext.size in 48..RelayEnvelopeFormat.MAX_BYTES)
        requireRelay(localInstallId == outerContext.recipientInstallId && sender.isValid())
        requireRelay(sender.keyVersion == outerContext.senderKeyVersion)
        val innerBytes = identityStore.decryptRelay(ciphertext, RelayEnvelopeFormat.hpkeContext(context), outerContext.recipientKeyVersion)
        val inner = RelayEnvelopeFormat.parseCanonical(innerBytes)
        RelayEnvelopeFormat.requireFields(inner, setOf("context", "payload", "signature", "version"))
        requireRelay(inner.opt("version") == 1)
        val signedContext = inner.opt("context") as? JSONObject ?: rejectRelay()
        requireRelay(RelayEnvelopeFormat.canonical(signedContext) == RelayEnvelopeFormat.canonical(context))
        val payload = inner.opt("payload") as? JSONObject ?: rejectRelay()
        val signature = RelayEnvelopeFormat.decode(inner.opt("signature") as? String ?: rejectRelay(), 64)
        inner.remove("signature")
        RelayEnvelopeFormat.verify(sender.signingPublicKey, signature, RelayEnvelopeFormat.signingInput(inner))
        VerifiedPayload(payload)
    }

    // No upstream exception (which could include hostile JSON/key material) crosses this boundary.
    private inline fun <T> safely(action: () -> T): T = try { action() } catch (_: Exception) { rejectRelay() }
}

internal fun rejectRelay(): Nothing = throw GeneralSecurityException("Invalid relay envelope")
internal fun requireRelay(condition: Boolean) { if (!condition) rejectRelay() }

/**
 * Versioned serialization, not cryptography. Exact decimal numbers are retained as BigDecimal;
 * no JSON parser may round through binary64. Precision and |scale| <= 100 after stripping zeroes.
 * Strings preserve Unicode scalars; object order is lexicographic UTF-16, matching Kotlin String.
 * Escapes are JSON short escapes for BS/FF/LF/CR/TAB, lowercase \u00xx for other controls,
 * and backslash escapes for quote/backslash only. Unpaired surrogates are rejected.
 * Incoming bytes MUST already be canonical. Duplicate/extra schema members are never ignored.
 */
internal object RelayEnvelopeFormat {
    const val MAX_BYTES = 512 * 1024
    private const val MAX_DEPTH = 64

    fun canonical(value: Any): String {
        val out = StringBuilder()
        fun append(item: Any?, depth: Int) {
            requireRelay(depth <= MAX_DEPTH)
            when (item) {
                null, JSONObject.NULL -> out.append("null")
                is JSONObject -> {
                    out.append('{')
                    item.keys().asSequence().toList().sorted().forEachIndexed { index, key ->
                        if (index > 0) out.append(',')
                        out.append(quote(key)).append(':')
                        append(item.get(key), depth + 1)
                    }
                    out.append('}')
                }
                is JSONArray -> {
                    out.append('[')
                    for (index in 0 until item.length()) {
                        if (index > 0) out.append(',')
                        append(item.get(index), depth + 1)
                    }
                    out.append(']')
                }
                is String -> out.append(quote(item))
                is Boolean -> out.append(if (item) "true" else "false")
                is Number -> out.append(number(item.toString()).toPlainString())
                else -> rejectRelay()
            }
            requireRelay(out.length <= MAX_BYTES)
        }
        append(value, 0)
        return out.toString().also { requireRelay(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) }
    }

    fun parseCanonical(bytes: ByteArray): JSONObject {
        requireRelay(bytes.size <= MAX_BYTES)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val value = Parser(text).read() as? JSONObject ?: rejectRelay()
        requireRelay(canonical(value) == text)
        return value
    }

    fun hpkeContext(context: JSONObject): ByteArray = ("dosefolk-relay-hpke-v1\u0000" + canonical(context)).toByteArray(Charsets.UTF_8)
    fun signingInput(unsigned: JSONObject): ByteArray = ("dosefolk-relay-signature-v1\u0000" + canonical(unsigned)).toByteArray(Charsets.UTF_8)
    fun pairingSigningInput(unsigned: JSONObject): ByteArray =
        ("dosefolk-relay-pairing-v2\u0000" + canonical(unsigned)).toByteArray(Charsets.UTF_8)
    fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    fun decode(text: String, size: Int): ByteArray {
        requireRelay(text.matches(Regex("[A-Za-z0-9_-]+")))
        val bytes = try { Base64.getUrlDecoder().decode(text) } catch (_: IllegalArgumentException) { rejectRelay() }
        requireRelay(bytes.size == size && encode(bytes) == text)
        return bytes
    }

    fun verify(publicKey: String, signature: ByteArray, message: ByteArray) {
        SignatureConfig.register()
        val key = Ed25519PublicKey.create(Ed25519Parameters.Variant.NO_PREFIX, Bytes.copyFrom(decode(publicKey, 32)), null)
        KeysetHandle.newBuilder().addEntry(KeysetHandle.importKey(key).withFixedId(1).makePrimary()).build()
            .getPrimitive(RegistryConfiguration.get(), PublicKeyVerify::class.java).verify(signature, message)
    }

    fun requireFields(objectValue: JSONObject, names: Set<String>) = requireRelay(objectValue.keys().asSequence().toSet() == names)
    fun requireOpaqueId(value: String) = requireRelay(value.isNotEmpty() && value.length <= 256 &&
        value.none { it.isWhitespace() || it.isISOControl() })

    /** Pairing byte/schema/signature contract ONLY: no expiry clock, replay state, routes or trust activation. */
    fun verifyPairing(bytes: ByteArray): JSONObject = try {
        val offer = parseCanonical(bytes)
        requireFields(offer, setOf("version", "offerId", "installId", "encryptionPublicKey", "signingPublicKey",
            "keyVersion", "pairingSecret", "expiresAt", "signature"))
        requireRelay(offer.opt("version") == 2 && (offer.opt("keyVersion") as? Int ?: 0) > 0)
        requireOpaqueId(offer.opt("offerId") as? String ?: rejectRelay())
        requireOpaqueId(offer.opt("installId") as? String ?: rejectRelay())
        val encryption = decode(offer.opt("encryptionPublicKey") as? String ?: rejectRelay(), 32)
        val signingKey = offer.opt("signingPublicKey") as? String ?: rejectRelay()
        val signing = decode(signingKey, 32)
        requireRelay(encryption.any { it != 0.toByte() } && signing.any { it != 0.toByte() } && !encryption.contentEquals(signing))
        decode(offer.opt("pairingSecret") as? String ?: rejectRelay(), 32)
        val expiry = offer.opt("expiresAt") as? String ?: rejectRelay()
        requireRelay(expiry.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z")))
        requireRelay(java.time.Instant.parse(expiry).toString() == expiry)
        val signatureText = offer.opt("signature") as? String ?: rejectRelay()
        val signature = decode(signatureText, 64)
        offer.remove("signature")
        verify(signingKey, signature, pairingSigningInput(offer))
        offer.put("signature", signatureText)
    } catch (_: Exception) { rejectRelay() }

    private fun number(raw: String): BigDecimal {
        requireRelay(raw.length <= 220 && raw.matches(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")))
        val value = try { BigDecimal(raw).stripTrailingZeros() } catch (_: NumberFormatException) { rejectRelay() }
        requireRelay(value.precision() <= 100 && value.scale() in -100..100)
        return value
    }

    private fun quote(value: String): String {
        requireRelay(value.length <= MAX_BYTES)
        val out = StringBuilder("\"")
        var index = 0
        while (index < value.length) {
            val c = value[index++]
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '\u000c' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> when {
                    c.code < 32 -> out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                    c.isHighSurrogate() -> {
                        requireRelay(index < value.length && value[index].isLowSurrogate())
                        out.append(c).append(value[index++])
                    }
                    c.isLowSurrogate() -> rejectRelay()
                    else -> out.append(c)
                }
            }
        }
        return out.append('"').toString()
    }

    private class Parser(private val text: String) {
        private var offset = 0
        fun read(): Any {
            val result = value(0)
            requireRelay(offset == text.length)
            return result
        }
        private fun value(depth: Int): Any {
            requireRelay(depth <= MAX_DEPTH && offset < text.length)
            return when (text[offset]) {
                '{' -> {
                    offset++
                    val objectValue = JSONObject()
                    if (take('}')) return objectValue
                    do {
                        val key = string()
                        requireRelay(!objectValue.has(key) && take(':'))
                        objectValue.put(key, value(depth + 1))
                    } while (take(','))
                    requireRelay(take('}'))
                    objectValue
                }
                '[' -> {
                    offset++
                    val array = JSONArray()
                    if (take(']')) return array
                    do { array.put(value(depth + 1)) } while (take(','))
                    requireRelay(take(']'))
                    array
                }
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", JSONObject.NULL)
                else -> {
                    val start = offset
                    while (offset < text.length && text[offset] in "-+0123456789.eE") offset++
                    val decimal = number(text.substring(start, offset))
                    try { decimal.intValueExact() } catch (_: ArithmeticException) {
                        try { decimal.longValueExact() } catch (_: ArithmeticException) { decimal }
                    }
                }
            }
        }
        private fun literal(word: String, result: Any): Any {
            requireRelay(text.startsWith(word, offset)); offset += word.length; return result
        }
        private fun take(c: Char): Boolean = if (offset < text.length && text[offset] == c) { offset++; true } else false
        private fun string(): String {
            requireRelay(take('"'))
            val result = StringBuilder()
            while (offset < text.length) {
                val c = text[offset++]
                if (c == '"') return result.toString().also { quote(it) }
                requireRelay(c.code >= 32)
                if (c != '\\') { result.append(c); continue }
                requireRelay(offset < text.length)
                when (val escaped = text[offset++]) {
                    '"', '\\', '/' -> result.append(escaped)
                    'b' -> result.append('\b')
                    'f' -> result.append('\u000c')
                    'n' -> result.append('\n')
                    'r' -> result.append('\r')
                    't' -> result.append('\t')
                    'u' -> {
                        requireRelay(offset + 4 <= text.length)
                        val digits = text.substring(offset, offset + 4)
                        requireRelay(digits.matches(Regex("[0-9a-fA-F]{4}")))
                        result.append(digits.toInt(16).toChar()); offset += 4
                    }
                    else -> rejectRelay()
                }
            }
            rejectRelay()
        }
    }
}
