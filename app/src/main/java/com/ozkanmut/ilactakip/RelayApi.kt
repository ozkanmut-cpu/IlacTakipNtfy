package com.ozkanmut.ilactakip

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.GeneralSecurityException
import java.util.Base64

/** Small authenticated relay boundary. It never logs or exposes request values. */
data class RelayApiRequest(val method: String, val path: String, val headers: Map<String, String>, val body: JSONObject) {
    override fun toString(): String = "RelayApiRequest(redacted)"
}
data class RelayApiResponse(val status: Int, val body: JSONObject) {
    override fun toString(): String = "RelayApiResponse(redacted)"
}
fun interface RelayApiHttp { fun execute(request: RelayApiRequest): RelayApiResponse }

data class RelayRoute(val routeId: String, val recipientInstallId: String, val recipientIdentity: RelayPublicIdentity)
data class RelayOutboundEnvelope(val outerContext: RelayOuterContext, val ciphertext: ByteArray) {
    override fun toString(): String = "RelayOutboundEnvelope(redacted)"
}
data class RelayInboxEnvelope(
    val outerContext: RelayOuterContext, val ciphertext: ByteArray, val relaySeq: Long,
    val receivedAt: Long, val expiresAt: Long
) {
    override fun toString(): String = "RelayInboxEnvelope(redacted)"
}
data class RelayTerminalAck(val messageId: String, val outcome: String)

class RelayApi(baseUrl: String, credential: String, private val http: RelayApiHttp) {
    private val baseUrl = validateBaseUrl(baseUrl)
    private val authorization = credential.trim().takeIf { it.isNotEmpty() }?.let { "Bearer $it" } ?: rejectRelay()

    fun routes(): List<RelayRoute> = safe {
        val body = call("GET", "/v1/routes", expectedStatus = 200)
        requireFields(body, setOf("routes"))
        val routes = body.opt("routes") as? JSONArray ?: rejectRelay()
        (0 until routes.length()).map { index ->
            val value = routes.opt(index) as? JSONObject ?: rejectRelay()
            requireFields(value, setOf("routeId", "recipientInstallId", "encryptionPublicKey", "signingPublicKey", "keyVersion"))
            val routeId = opaque(value, "routeId")
            val install = opaque(value, "recipientInstallId")
            val encryption = value.opt("encryptionPublicKey") as? String ?: rejectRelay()
            val signing = value.opt("signingPublicKey") as? String ?: rejectRelay()
            val version = positiveInt(value, "keyVersion")
            val identity = RelayPublicIdentity(encryption, signing, version, RelayPublicIdentity.fingerprintFor(encryption, signing, version))
            requireRelay(identity.isValid())
            RelayRoute(routeId, install, identity)
        }
    }

    fun enqueue(envelope: RelayOutboundEnvelope) = safe {
        val c = envelope.outerContext
        val wire = JSONObject().put("messageId", c.messageId).put("routeId", c.routeId)
            .put("recipientInstallId", c.recipientInstallId).put("senderKeyVersion", c.senderKeyVersion)
            .put("recipientKeyVersion", c.recipientKeyVersion)
            .put("ciphertext", Base64.getEncoder().encodeToString(envelope.ciphertext))
        val response = call("POST", "/v1/messages", JSONObject().put("messages", JSONArray().put(wire)), 201)
        requireFields(response, setOf("messages"))
        val results = response.opt("messages") as? JSONArray ?: rejectRelay()
        requireRelay(results.length() == 1)
        val result = results.optJSONObject(0) ?: rejectRelay()
        requireFields(result, setOf("messageId", "inserted", "relaySeq"))
        requireRelay(result.opt("messageId") == c.messageId && result.opt("inserted") is Boolean && positiveLong(result, "relaySeq") > 0)
    }

    fun inbox(): List<RelayInboxEnvelope> = safe {
        val body = call("GET", "/v1/inbox?limit=100", expectedStatus = 200)
        requireFields(body, setOf("messages"))
        val messages = body.opt("messages") as? JSONArray ?: rejectRelay()
        requireRelay(messages.length() <= 100)
        (0 until messages.length()).map { index ->
            val value = messages.opt(index) as? JSONObject ?: rejectRelay()
            requireFields(value, setOf("messageId", "routeId", "senderInstallId", "recipientInstallId", "senderKeyVersion", "recipientKeyVersion", "ciphertext", "relaySeq", "receivedAt", "expiresAt"))
            val context = RelayOuterContext(opaque(value, "messageId"), opaque(value, "routeId"), opaque(value, "senderInstallId"), opaque(value, "recipientInstallId"), positiveInt(value, "senderKeyVersion"), positiveInt(value, "recipientKeyVersion"))
            val ciphertext = try { Base64.getDecoder().decode(value.opt("ciphertext") as? String ?: rejectRelay()) } catch (_: Exception) { rejectRelay() }
            requireRelay(ciphertext.isNotEmpty() && Base64.getEncoder().encodeToString(ciphertext) == value.getString("ciphertext"))
            RelayInboxEnvelope(context, ciphertext, positiveLong(value, "relaySeq"), positiveLong(value, "receivedAt"), positiveLong(value, "expiresAt"))
        }
    }

    fun acknowledge(acks: List<RelayTerminalAck>) = safe {
        requireRelay(acks.size in 1..100)
        val items = JSONArray()
        acks.forEach { ack ->
            RelayEnvelopeFormat.requireOpaqueId(ack.messageId)
            requireRelay(ack.outcome in setOf("processed", "duplicate", "rejected"))
            items.put(JSONObject().put("messageId", ack.messageId).put("outcome", ack.outcome))
        }
        val body = call("POST", "/v1/messages/ack", JSONObject().put("acks", items), 200)
        requireFields(body, setOf("ok")); requireRelay(body.opt("ok") == true)
    }

    private fun call(method: String, path: String, body: JSONObject = JSONObject(), expectedStatus: Int): JSONObject {
        val response = try { http.execute(RelayApiRequest(method, path, mapOf("Authorization" to authorization), body)) }
        catch (_: Exception) { throw IOException("Relay request failed") }
        if (response.status != expectedStatus) throw IOException("Relay request failed")
        return response.body
    }

    private inline fun <T> safe(action: () -> T): T = try { action() }
    catch (e: GeneralSecurityException) { throw e }
    catch (e: IOException) { throw e }
    catch (_: Exception) { rejectRelay() }

    private fun opaque(value: JSONObject, name: String): String = (value.opt(name) as? String ?: rejectRelay()).also(RelayEnvelopeFormat::requireOpaqueId)
    private fun positiveInt(value: JSONObject, name: String): Int = (value.opt(name) as? Int ?: rejectRelay()).also { requireRelay(it > 0) }
    private fun positiveLong(value: JSONObject, name: String): Long = when (val n = value.opt(name)) { is Int -> n.toLong(); is Long -> n; else -> rejectRelay() }.also { requireRelay(it > 0) }
    private fun requireFields(value: JSONObject, names: Set<String>) = requireRelay(value.keys().asSequence().toSet() == names)

    companion object {
        private fun validateBaseUrl(raw: String): String = try {
            val uri = URI(raw.trim())
            requireRelay(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
                uri.query == null && uri.fragment == null && (uri.path.isNullOrEmpty() || uri.path == "/") &&
                (uri.port == -1 || uri.port == 443))
            uri.toString().removeSuffix("/")
        } catch (_: Exception) { rejectRelay() }
    }
}

private class UrlRelayHttp(private val baseUrl: String) : RelayApiHttp {
    override fun execute(request: RelayApiRequest): RelayApiResponse {
        val connection = (URL(baseUrl + request.path).openConnection() as? HttpURLConnection) ?: throw IOException()
        try {
            connection.requestMethod = request.method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000; connection.readTimeout = 20_000
            connection.setRequestProperty("Accept", "application/json")
            request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (request.method == "POST") {
                connection.doOutput = true; connection.setRequestProperty("Content-Type", "application/json")
                BufferedOutputStream(connection.outputStream).use { it.write(request.body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { readBounded(it) } ?: "{}"
            return RelayApiResponse(connection.responseCode, JSONObject(text))
        } finally { connection.disconnect() }
    }

    private fun readBounded(stream: java.io.InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            if (output.size() + count > MAX_RESPONSE_BYTES) throw IOException()
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private companion object { const val MAX_RESPONSE_BYTES = 1_048_576 }
}
