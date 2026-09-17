package com.ozkanmut.ilactakip

import android.content.Context
import com.google.crypto.tink.AccessesPartialKey
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.hybrid.HpkeParameters
import com.google.crypto.tink.hybrid.HpkePublicKey
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.integration.android.AndroidKeystore
import com.google.crypto.tink.integration.android.AndroidKeystoreKmsClient
import com.google.crypto.tink.signature.Ed25519Parameters
import com.google.crypto.tink.signature.Ed25519PublicKey
import com.google.crypto.tink.signature.SignatureConfig
import org.json.JSONObject
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.Base64

/** Public wire values only. Private keys and Tink keysets are never part of this DTO. */
data class RelayPublicIdentity(
    val encryptionPublicKey: String,
    val signingPublicKey: String,
    val keyVersion: Int,
    val fingerprint: String
) {
    fun toJson(): JSONObject = JSONObject()
        .put("encryptionPublicKey", encryptionPublicKey)
        .put("signingPublicKey", signingPublicKey)
        .put("keyVersion", keyVersion)
        .put("fingerprint", fingerprint)

    internal fun isValid(): Boolean = keyVersion > 0 &&
        isCanonicalPublicKey(encryptionPublicKey) && isCanonicalPublicKey(signingPublicKey) &&
        encryptionPublicKey != signingPublicKey &&
        fingerprint.matches(Regex("[0-9a-f]{64}")) &&
        fingerprint == fingerprintFor(encryptionPublicKey, signingPublicKey, keyVersion)

    internal companion object {
        fun fingerprintFor(encryptionKey: String, signingKey: String, version: Int): String {
            val canonical = "dosefolk-relay-identity-v1\u0000$version\u0000$encryptionKey\u0000$signingKey"
            return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }

        private fun isCanonicalPublicKey(encoded: String): Boolean {
            if (!encoded.matches(Regex("[A-Za-z0-9_-]{43}"))) return false
            return try {
                val bytes = Base64.getUrlDecoder().decode(encoded)
                bytes.size == 32 && bytes.any { it != 0.toByte() } &&
                    Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == encoded
            } catch (_: IllegalArgumentException) {
                false
            }
        }

        fun fromJson(json: JSONObject): RelayPublicIdentity? {
            if (json.keys().asSequence().toSet() != setOf(
                    "encryptionPublicKey", "signingPublicKey", "keyVersion", "fingerprint"
                )) return null
            val identity = RelayPublicIdentity(
                json.opt("encryptionPublicKey") as? String ?: return null,
                json.opt("signingPublicKey") as? String ?: return null,
                json.opt("keyVersion") as? Int ?: return null,
                json.opt("fingerprint") as? String ?: return null
            )
            return identity.takeIf { it.isValid() }
        }
    }
}

/** Device-owned identity. Call off the UI thread: creation and loading perform durable disk I/O. */
class RelayIdentityStore internal constructor(context: Context, private val masterAeadSource: RelayMasterAeadSource) {
    /** The public production entry point always requires Android Keystore protection. */
    constructor(context: Context) : this(context, AndroidRelayMasterAeadSource)

    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val peerPreferences = context.applicationContext
        .getSharedPreferences("dosefolk_relay_peers", Context.MODE_PRIVATE)

    /** Reopens encrypted persistent keysets; no process-global identity or private export exists. */
    @AccessesPartialKey
    fun publicIdentity(): RelayPublicIdentity = synchronized(identityLock) {
        HybridConfig.register()
        SignatureConfig.register()
        val stored = preferences.all
        if (stored.isEmpty()) {
            // Empty preferences are not proof of a new installation: the identity may have been
            // deleted, or bootstrap interrupted after its master was created. Never replace it.
            if (masterAeadSource.exists() || peerPreferences.all.isNotEmpty()) {
                throw GeneralSecurityException("Relay identity is missing but prior identity or trust state remains")
            }
            return@synchronized createIdentity()
        }
        if (stored.keys != setOf(ENCRYPTION_KEYSET, SIGNING_KEYSET, KEY_VERSION)) {
            throw GeneralSecurityException("Incomplete relay identity storage")
        }
        val version = stored[KEY_VERSION] as? Int
            ?: throw GeneralSecurityException("Invalid relay identity version")
        if (version <= 0) throw GeneralSecurityException("Invalid relay identity version")

        // Existing identities never regenerate their wrapping key after a restore or Keystore loss.
        val master = masterAeadSource.getExisting()
        val encryption = readEncryptedKeyset(stored[ENCRYPTION_KEYSET], master, version, "hpke")
        val signing = readEncryptedKeyset(stored[SIGNING_KEYSET], master, version, "ed25519")
        exportPublic(encryption, signing, version)
    }

    @AccessesPartialKey
    private fun createIdentity(): RelayPublicIdentity {
        // Unlike AndroidKeysetManager, this API throws on Keystore failure and has no plaintext fallback.
        val master = masterAeadSource.createNew()
        val encryption = KeysetHandle.generateNew(encryptionParameters())
        val signing = KeysetHandle.generateNew(signingParameters())
        val version = 1
        val publicIdentity = exportPublic(encryption, signing, version)
        val encryptionCiphertext = writeEncryptedKeyset(encryption, master, version, "hpke")
        val signingCiphertext = writeEncryptedKeyset(signing, master, version, "ed25519")

        // Both independently generated keys and their version become visible in a single commit.
        if (!preferences.edit()
                .putString(ENCRYPTION_KEYSET, encryptionCiphertext)
                .putString(SIGNING_KEYSET, signingCiphertext)
                .putInt(KEY_VERSION, version)
                .commit()) {
            // A failed SharedPreferences commit still changes its in-memory view. Do not let a
            // subsequent reconstruction export that uncommitted identity as if it were durable.
            preferences.edit().remove(ENCRYPTION_KEYSET).remove(SIGNING_KEYSET).remove(KEY_VERSION).commit()
            throw IOException("Could not persist relay identity")
        }
        return publicIdentity
    }

    private fun writeEncryptedKeyset(handle: KeysetHandle, master: Aead, version: Int, purpose: String): String =
        Base64.getEncoder().encodeToString(TinkProtoKeysetFormat.serializeEncryptedKeyset(
            handle, master, associatedData(version, purpose), RegistryConfiguration.get()
        ))

    private fun readEncryptedKeyset(value: Any?, master: Aead, version: Int, purpose: String): KeysetHandle {
        val encoded = value as? String ?: throw GeneralSecurityException("Missing encrypted relay keyset")
        val ciphertext = try {
            Base64.getDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            throw GeneralSecurityException("Invalid encrypted relay keyset")
        }
        // This API decrypts an EncryptedKeyset only; it never retries as a cleartext keyset.
        return TinkProtoKeysetFormat.parseEncryptedKeyset(
            ciphertext, master, associatedData(version, purpose), RegistryConfiguration.get()
        )
    }

    @AccessesPartialKey
    private fun exportPublic(encryption: KeysetHandle, signing: KeysetHandle, version: Int): RelayPublicIdentity {
        if (encryption.size() != 1 || signing.size() != 1) {
            throw GeneralSecurityException("Unexpected relay keyset profile")
        }
        // Obtain public-only handles BEFORE accessing key bytes. Never serialize a private handle in cleartext.
        val encryptionKey = encryption.publicKeysetHandle.primary.key as? HpkePublicKey
            ?: throw GeneralSecurityException("Unexpected relay encryption key type")
        val signingKey = signing.publicKeysetHandle.primary.key as? Ed25519PublicKey
            ?: throw GeneralSecurityException("Unexpected relay signing key type")
        if (encryptionKey.parameters != encryptionParameters() || signingKey.parameters != signingParameters()) {
            throw GeneralSecurityException("Unexpected relay key parameters")
        }
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val encryptionPublicKey = encoder.encodeToString(encryptionKey.publicKeyBytes.toByteArray())
        val signingPublicKey = encoder.encodeToString(signingKey.publicKeyBytes.toByteArray())
        return RelayPublicIdentity(
            encryptionPublicKey, signingPublicKey, version,
            RelayPublicIdentity.fingerprintFor(encryptionPublicKey, signingPublicKey, version)
        ).also {
            if (!it.isValid() || encryptionPublicKey == signingPublicKey) {
                throw GeneralSecurityException("Invalid relay public identity")
            }
        }
    }

    private fun encryptionParameters(): HpkeParameters = HpkeParameters.builder()
        .setKemId(HpkeParameters.KemId.DHKEM_X25519_HKDF_SHA256)
        .setKdfId(HpkeParameters.KdfId.HKDF_SHA256)
        .setAeadId(HpkeParameters.AeadId.CHACHA20_POLY1305)
        .setVariant(HpkeParameters.Variant.NO_PREFIX)
        .build()

    private fun signingParameters(): Ed25519Parameters = Ed25519Parameters.create(Ed25519Parameters.Variant.NO_PREFIX)

    private fun associatedData(version: Int, purpose: String): ByteArray =
        "dosefolk-relay-keyset-v1\u0000$version\u0000$purpose".toByteArray(Charsets.UTF_8)

    private companion object {
        // Only synchronization is static, never keysets, key bytes, or identity metadata.
        val identityLock = Any()
        const val PREFERENCES = "dosefolk_relay_identity"
        const val ENCRYPTION_KEYSET = "encrypted_hpke_keyset"
        const val SIGNING_KEYSET = "encrypted_ed25519_keyset"
        const val KEY_VERSION = "key_version"
    }
}

/** Internal platform boundary; provides only an AEAD primitive, never wrapping-key bytes. */
internal interface RelayMasterAeadSource {
    fun exists(): Boolean
    fun createNew(): Aead
    fun getExisting(): Aead
}

private object AndroidRelayMasterAeadSource : RelayMasterAeadSource {
    private const val MASTER_KEY_ALIAS = "dosefolk_relay_identity_master_v1"
    private const val MASTER_KEY_URI = "android-keystore://$MASTER_KEY_ALIAS"

    override fun exists(): Boolean = AndroidKeystore.hasKey(MASTER_KEY_ALIAS)
    override fun createNew(): Aead {
        // Tink atomically refuses an existing alias; never reuse/overwrite a surviving master.
        AndroidKeystoreKmsClient.generateNewAeadKey(MASTER_KEY_URI)
        return getExisting()
    }
    override fun getExisting(): Aead = AndroidKeystoreKmsClient().getAead(MASTER_KEY_URI)
}
