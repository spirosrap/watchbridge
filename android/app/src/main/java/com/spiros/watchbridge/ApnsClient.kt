package com.spiros.watchbridge

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Talks to APNs directly over HTTP/2 using token-based (JWT, ES256) authentication.
 * No relay server: the phone signs its own provider tokens with the .p8 key.
 */
object ApnsClient {
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
    private val json = "application/json".toMediaType()
    private val sendLock = Mutex()

    private var jwtCache: Pair<String, Long>? = null
    private const val JWT_LIFETIME_S = 45 * 60L // Apple allows 20..60 minutes

    data class Result(val ok: Boolean, val status: Int, val reason: String)

    fun parseKey(pem: String): PrivateKey {
        val body = pem.lines()
            .filter { it.isNotBlank() && !it.startsWith("-----") }
            .joinToString("")
        val der = Base64.getDecoder().decode(body)
        return KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(der))
    }

    @Synchronized
    fun invalidateJwt() {
        jwtCache = null
    }

    @Synchronized
    private fun jwt(teamId: String, keyId: String, pem: String): String {
        val now = System.currentTimeMillis() / 1000
        jwtCache?.let { (tok, issued) -> if (now - issued < JWT_LIFETIME_S) return tok }
        val header = b64url("""{"alg":"ES256","kid":"$keyId"}""".toByteArray())
        val claims = b64url("""{"iss":"$teamId","iat":$now}""".toByteArray())
        val input = "$header.$claims"
        val sig = Signature.getInstance("SHA256withECDSA").apply {
            initSign(parseKey(pem))
            update(input.toByteArray())
        }.sign()
        val token = "$input.${b64url(derToJose(sig))}"
        jwtCache = token to now
        return token
    }

    private fun b64url(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

    /** DER SEQUENCE{INTEGER r, INTEGER s} -> raw 64-byte r||s as JOSE requires. */
    private fun derToJose(der: ByteArray): ByteArray {
        var i = 1
        val seqLen = der[i].toInt() and 0xff
        i += if (seqLen and 0x80 != 0) 1 + (seqLen and 0x7f) else 1
        fun readInt(): ByteArray {
            require(der[i] == 0x02.toByte()) { "bad DER signature" }
            val len = der[i + 1].toInt() and 0xff
            val v = der.copyOfRange(i + 2, i + 2 + len)
            i += 2 + len
            return v
        }
        val r = readInt()
        val s = readInt()
        val out = ByteArray(64)
        fun put(v: ByteArray, off: Int) {
            val t = if (v.size > 32) v.copyOfRange(v.size - 32, v.size) else v
            System.arraycopy(t, 0, out, off + 32 - t.size, t.size)
        }
        put(r, 0)
        put(s, 32)
        return out
    }

    private fun collapseId(key: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }

    /** Build and send a notification for a forwarded Android notification. */
    suspend fun forward(appName: String, title: String, text: String, pkg: String, key: String): Result {
        val alert = JSONObject()
        if (title.isNotEmpty()) {
            alert.put("title", title)
            alert.put("subtitle", appName)
        } else {
            alert.put("title", appName)
        }
        if (text.isNotEmpty()) alert.put("body", text.take(1500))
        val aps = JSONObject()
            .put("alert", alert)
            .put("sound", "default")
            .put("thread-id", pkg)
            .put("category", "PIXEL")
        val payload = JSONObject()
            .put("aps", aps)
            .put("pkg", pkg)
            .put("app", appName)
        return send(payload, collapseId(key), "$appName: ${title.ifEmpty { text }.take(40)}")
    }

    suspend fun sendTest(): Result {
        val aps = JSONObject()
            .put("alert", JSONObject().put("title", "WatchBridge").put("body", "Test notification from your Pixel"))
            .put("sound", "default")
            .put("category", "PIXEL")
        return send(JSONObject().put("aps", aps).put("pkg", "test"), null, "test push")
    }

    private suspend fun send(payload: JSONObject, collapseId: String?, label: String): Result =
        sendLock.withLock { sendLocked(payload, collapseId, label) }

    private fun sendLocked(payload: JSONObject, collapseId: String?, label: String): Result {
        val s = Prefs.state.value
        val pem = Prefs.readKey()
        if (pem == null || !s.keyConfigured) {
            return fail(0, "APNs key / Team ID / Key ID not configured", label)
        }
        if (s.deviceToken.isEmpty()) return fail(0, "No watch device token", label)

        val host = if (s.sandbox) "api.sandbox.push.apple.com" else "api.push.apple.com"
        for (attempt in 1..2) {
            val req = Request.Builder()
                .url("https://$host/3/device/${s.deviceToken}")
                .post(payload.toString().toRequestBody(json))
                .header("authorization", "bearer ${jwt(s.teamId, s.keyId, pem)}")
                .header("apns-topic", s.bundleId)
                .header("apns-push-type", "alert")
                .header("apns-priority", "10")
                .header("apns-expiration", "0")
                .apply { if (collapseId != null) header("apns-collapse-id", collapseId) }
                .build()
            val outcome: Result? = try {
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        Prefs.recordResult(true, "sent $label")
                        Result(true, resp.code, "")
                    } else {
                        val reason = runCatching { JSONObject(body).optString("reason") }.getOrNull().orEmpty()
                        if (resp.code == 403 && reason.contains("ProviderToken") && attempt == 1) {
                            invalidateJwt()
                            null // retry once with a fresh JWT
                        } else {
                            fail(resp.code, reason.ifEmpty { body.ifEmpty { resp.message } }, label)
                        }
                    }
                }
            } catch (e: Exception) {
                fail(0, e.javaClass.simpleName + ": " + (e.message ?: ""), label)
            }
            if (outcome != null) return outcome
        }
        return fail(403, "provider token rejected twice", label)
    }

    private fun fail(status: Int, reason: String, label: String): Result {
        val human = when (reason) {
            "BadDeviceToken" -> "BadDeviceToken (wrong token, or sandbox/production mismatch)"
            "DeviceTokenNotForTopic" -> "DeviceTokenNotForTopic (bundle ID mismatch)"
            "InvalidProviderToken" -> "InvalidProviderToken (check Team ID, Key ID, .p8)"
            "TopicDisallowed" -> "TopicDisallowed (push capability missing on App ID)"
            else -> reason
        }
        Prefs.recordResult(false, "FAILED $label: ${if (status > 0) "$status " else ""}$human")
        return Result(false, status, human)
    }
}
