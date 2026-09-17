package com.ozkanmut.ilactakip

import android.content.Context
import org.json.JSONException
import org.json.JSONObject

/**
 * Locally authenticated peer pins. Server metadata may be compared, never used to replace a pin.
 * Call off the UI thread: pin/revoke perform synchronous disk commits, and initial reads may wait for disk.
 */
class RelayPeerStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("dosefolk_relay_peers", Context.MODE_PRIVATE)

    /** Call only after authenticated pairing. Re-pair/rotation are deliberately not implemented here. */
    fun pin(installId: String, identity: RelayPublicIdentity): Boolean = synchronized(peerLock) {
        if (!validInstallId(installId) || !identity.isValid() || revoked(installId)) return@synchronized false
        val key = pinKey(installId)
        if (preferences.contains(key)) {
            // A corrupt record is not an absent record and must not be replaced with new trust.
            return@synchronized readPin(installId) == identity
        }
        val committed = preferences.edit().putString(key, identity.toJson().toString()).commit()
        if (!committed) {
            // commit() failure still updates the in-memory view; a failed pin must not grant trust.
            preferences.edit().remove(key).commit()
        }
        committed
    }

    /** Returns public metadata, including for revoked peers; use isTrusted for authorization. */
    fun pinnedIdentity(installId: String): RelayPublicIdentity? = synchronized(peerLock) {
        if (!validInstallId(installId)) null else readPin(installId)
    }

    fun isTrusted(installId: String, identity: RelayPublicIdentity): Boolean = synchronized(peerLock) {
        validInstallId(installId) && identity.isValid() && !revoked(installId) && readPin(installId) == identity
    }

    fun revoke(installId: String): Boolean = synchronized(peerLock) {
        if (!validInstallId(installId)) return@synchronized false
        // Persist even before the first pin, fencing off any delayed pairing result.
        preferences.edit().putBoolean(revocationKey(installId), true).commit()
    }

    fun isRevoked(installId: String): Boolean = synchronized(peerLock) {
        validInstallId(installId) && revoked(installId)
    }

    private fun revoked(installId: String): Boolean = preferences.contains(revocationKey(installId))

    private fun readPin(installId: String): RelayPublicIdentity? = try {
        preferences.getString(pinKey(installId), null)?.let { RelayPublicIdentity.fromJson(JSONObject(it)) }
    } catch (_: JSONException) {
        null
    } catch (_: ClassCastException) {
        null
    }

    private fun pinKey(installId: String): String = "pin:$installId"
    private fun revocationKey(installId: String): String = "revoked:$installId"
    private fun validInstallId(installId: String): Boolean = installId.isNotEmpty() &&
        installId.length <= 256 && installId.none { it.isWhitespace() || it.isISOControl() }

    private companion object {
        // Shared across store instances so check-and-write is atomic within the app's single process.
        val peerLock = Any()
    }
}
