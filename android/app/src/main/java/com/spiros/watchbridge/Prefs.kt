package com.spiros.watchbridge

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Settings(
    val teamId: String = "",
    val keyId: String = "",
    val bundleId: String = "com.spiros.WatchBridge",
    val hasKey: Boolean = false,
    val deviceToken: String = "",
    val sandbox: Boolean = true,
    val forwarding: Boolean = true,
    val blocked: Set<String> = DEFAULT_BLOCKED,
    val seenApps: Map<String, String> = emptyMap(),
    val log: List<String> = emptyList(),
    val sent: Int = 0,
    val failed: Int = 0,
    val lastResult: String = "",
    val listenerConnected: Boolean = false,
) {
    val keyConfigured get() = hasKey && teamId.length == 10 && keyId.length == 10
    val ready get() = keyConfigured && deviceToken.isNotEmpty()
}

val DEFAULT_BLOCKED = setOf(
    "android",
    "com.android.systemui",
    "com.android.vending",
    "com.google.android.gms",
    "com.google.android.apps.wellbeing",
    "com.spiros.watchbridge",
)

/** Single process-wide settings store backed by SharedPreferences; the .p8 key lives in a private file. */
object Prefs {
    private const val FILE = "watchbridge"
    private const val KEY_FILE = "apns_key.p8"
    private const val LOG_MAX = 60

    private lateinit var sp: SharedPreferences
    private lateinit var keyFile: File
    private val _state = MutableStateFlow(Settings())
    val state: StateFlow<Settings> get() = _state

    @Synchronized
    fun init(context: Context) {
        if (::sp.isInitialized) return
        val app = context.applicationContext
        sp = app.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        keyFile = File(app.filesDir, KEY_FILE)
        _state.value = load()
    }

    private fun load(): Settings {
        val blocked = sp.getStringSet("blocked", null) ?: DEFAULT_BLOCKED
        val seen = linkedMapOf<String, String>()
        runCatching {
            val o = JSONObject(sp.getString("seenApps", "{}") ?: "{}")
            for (k in o.keys()) seen[k] = o.getString(k)
        }
        val log = mutableListOf<String>()
        runCatching {
            val a = JSONArray(sp.getString("log", "[]") ?: "[]")
            for (i in 0 until a.length()) log += a.getString(i)
        }
        return Settings(
            teamId = sp.getString("teamId", "") ?: "",
            keyId = sp.getString("keyId", "") ?: "",
            bundleId = sp.getString("bundleId", "com.spiros.WatchBridge") ?: "com.spiros.WatchBridge",
            hasKey = keyFile.exists() && keyFile.length() > 0,
            deviceToken = sp.getString("deviceToken", "") ?: "",
            sandbox = sp.getBoolean("sandbox", true),
            forwarding = sp.getBoolean("forwarding", true),
            blocked = blocked.toSet(),
            seenApps = seen,
            log = log,
            sent = sp.getInt("sent", 0),
            failed = sp.getInt("failed", 0),
            lastResult = sp.getString("lastResult", "") ?: "",
            listenerConnected = _state.value.listenerConnected,
        )
    }

    @Synchronized
    private fun update(edit: SharedPreferences.Editor.() -> Unit, transform: (Settings) -> Settings) {
        sp.edit().apply(edit).apply()
        _state.value = transform(_state.value)
    }

    fun setTeamId(v: String) = update({ putString("teamId", v.trim()) }) { it.copy(teamId = v.trim()) }
    fun setKeyId(v: String) = update({ putString("keyId", v.trim()) }) { it.copy(keyId = v.trim()) }
    fun setBundleId(v: String) = update({ putString("bundleId", v.trim()) }) { it.copy(bundleId = v.trim()) }
    fun setSandbox(v: Boolean) = update({ putBoolean("sandbox", v) }) { it.copy(sandbox = v) }
    fun setForwarding(v: Boolean) = update({ putBoolean("forwarding", v) }) { it.copy(forwarding = v) }
    fun setDeviceToken(v: String) = update({ putString("deviceToken", v) }) { it.copy(deviceToken = v) }

    fun setListenerConnected(v: Boolean) {
        _state.value = _state.value.copy(listenerConnected = v)
    }

    fun setBlocked(pkg: String, blocked: Boolean) {
        val set = _state.value.blocked.toMutableSet()
        if (blocked) set += pkg else set -= pkg
        update({ putStringSet("blocked", set) }) { it.copy(blocked = set) }
    }

    /** Stores the PEM text of an APNs auth key. Returns an error message or null. */
    fun storeKey(pem: String): String? {
        val trimmed = pem.trim()
        if (!trimmed.contains("-----BEGIN PRIVATE KEY-----")) return "Not a PKCS#8 .p8 key"
        return try {
            ApnsClient.parseKey(trimmed) // validate
            keyFile.writeText(trimmed)
            update({}) { it.copy(hasKey = true) }
            ApnsClient.invalidateJwt()
            null
        } catch (e: Exception) {
            "Key rejected: ${e.message}"
        }
    }

    fun readKey(): String? = if (keyFile.exists()) keyFile.readText() else null

    fun clearKey() {
        keyFile.delete()
        update({}) { it.copy(hasKey = false) }
        ApnsClient.invalidateJwt()
    }

    fun noteApp(pkg: String, label: String) {
        if (_state.value.seenApps[pkg] == label) return
        val m = LinkedHashMap(_state.value.seenApps)
        m[pkg] = label
        val o = JSONObject()
        for ((k, v) in m) o.put(k, v)
        update({ putString("seenApps", o.toString()) }) { it.copy(seenApps = m) }
    }

    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun log(line: String) {
        val stamped = "${fmt.format(Date())}  $line"
        val l = (listOf(stamped) + _state.value.log).take(LOG_MAX)
        update({ putString("log", JSONArray(l).toString()) }) { it.copy(log = l) }
    }

    fun recordResult(ok: Boolean, summary: String) {
        val s = _state.value
        val sent = if (ok) s.sent + 1 else s.sent
        val failed = if (ok) s.failed else s.failed + 1
        update({
            putInt("sent", sent); putInt("failed", failed); putString("lastResult", summary)
        }) { it.copy(sent = sent, failed = failed, lastResult = summary) }
        log(summary)
    }

    fun clearLog() = update({ putString("log", "[]") }) { it.copy(log = emptyList()) }
}
