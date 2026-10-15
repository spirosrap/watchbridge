package com.spiros.watchbridge

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as SysSettings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF7FD1C0),
                    background = Color(0xFF0B1F2A),
                    surface = Color(0xFF10293A),
                ),
            ) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen()
                }
            }
        }
    }
}

private val TOKEN_RE = Regex("^[0-9a-fA-F]{64,}$")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val ctx = LocalContext.current
    val s by Prefs.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var listenerGranted by remember { mutableStateOf(isListenerEnabled(ctx)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { listenerGranted = isListenerEnabled(ctx) }
    }

    val pickKey = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val err = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
                }.fold(onSuccess = { Prefs.storeKey(it) }, onFailure = { "Could not read file: ${it.message}" })
            }
            Toast.makeText(ctx, err ?: "APNs key imported", Toast.LENGTH_LONG).show()
            if (err == null) Prefs.log("APNs key imported")
        }
    }

    var manualToken by remember { mutableStateOf("") }
    var showApps by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(true) }

    Scaffold(topBar = { TopAppBar(title = { Text("WatchBridge") }) }) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Section("Status") {
                    StatusRow(listenerGranted && s.listenerConnected, "Reading phone notifications")
                    StatusRow(s.keyConfigured, "APNs key configured")
                    StatusRow(s.deviceToken.isNotEmpty(), "Watch paired (device token)")
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Forward notifications", Modifier.weight(1f))
                        Switch(checked = s.forwarding, onCheckedChange = { Prefs.setForwarding(it) })
                    }
                    Text(
                        "Sent ${s.sent} · failed ${s.failed}" + if (s.lastResult.isNotEmpty()) "\nLast: ${s.lastResult}" else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            item {
                Section("1 · Notification access") {
                    Text(
                        if (listenerGranted) "Granted. WatchBridge can read notifications."
                        else "Allow WatchBridge as a notification listener.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(onClick = {
                        ctx.startActivity(Intent(SysSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }) { Text(if (listenerGranted) "Open settings" else "Grant access") }
                }
            }

            item {
                Section("2 · Apple push key") {
                    Text(
                        "Create an APNs auth key in the Apple Developer portal (Keys → + → Apple Push Notifications service), download the .p8 once and import it here.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = s.teamId, onValueChange = Prefs::setTeamId, label = { Text("Team ID") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = s.keyId, onValueChange = Prefs::setKeyId, label = { Text("Key ID") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = s.bundleId, onValueChange = Prefs::setBundleId, label = { Text("Watch app bundle ID") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalButton(onClick = { pickKey.launch(arrayOf("*/*")) }) {
                            Text(if (s.hasKey) "Replace .p8" else "Import .p8")
                        }
                        if (s.hasKey) TextButton(onClick = { Prefs.clearKey() }) { Text("Remove key") }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Sandbox environment")
                            Text(
                                "On for watch apps installed from Xcode. Off for TestFlight / App Store builds.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(checked = s.sandbox, onCheckedChange = { Prefs.setSandbox(it) })
                    }
                }
            }

            item {
                Section("3 · Pair the watch") {
                    Text(
                        "Open WatchBridge on the watch, swipe to the QR code and scan it.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = {
                            GmsBarcodeScanning.getClient(ctx).startScan()
                                .addOnSuccessListener { code ->
                                    val raw = code.rawValue?.trim().orEmpty()
                                    if (TOKEN_RE.matches(raw)) {
                                        Prefs.setDeviceToken(raw.lowercase())
                                        Prefs.log("watch token paired via QR")
                                        Toast.makeText(ctx, "Watch paired", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(ctx, "That QR code is not a device token", Toast.LENGTH_LONG).show()
                                    }
                                }
                                .addOnFailureListener {
                                    Toast.makeText(ctx, "Scan failed: ${it.message}", Toast.LENGTH_LONG).show()
                                }
                        }) { Text("Scan QR") }
                        if (s.deviceToken.isNotEmpty()) {
                            TextButton(onClick = { Prefs.setDeviceToken("") }) { Text("Unpair") }
                        }
                    }
                    if (s.deviceToken.isNotEmpty()) {
                        Text(
                            "Token ${s.deviceToken.take(8)}…${s.deviceToken.takeLast(6)}",
                            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = manualToken, onValueChange = { manualToken = it },
                        label = { Text("…or paste the token") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(enabled = TOKEN_RE.matches(manualToken.trim()), onClick = {
                        Prefs.setDeviceToken(manualToken.trim().lowercase())
                        Prefs.log("watch token entered manually")
                        manualToken = ""
                    }) { Text("Save token") }
                }
            }

            item {
                Section("4 · Test") {
                    Button(enabled = s.ready, onClick = {
                        scope.launch {
                            val r = ApnsClient.sendTest()
                            Toast.makeText(
                                ctx, if (r.ok) "Delivered to APNs" else "Failed: ${r.reason}", Toast.LENGTH_LONG,
                            ).show()
                        }
                    }) { Text("Send test notification") }
                    if (!s.ready) Text("Finish steps 2 and 3 first.", style = MaterialTheme.typography.bodySmall)
                }
            }

            item {
                Section("Apps", trailing = {
                    TextButton(onClick = { showApps = !showApps }) { Text(if (showApps) "Hide" else "Show") }
                }) {
                    Text(
                        "Apps that have posted notifications since install. Switch off the ones you don't want on the wrist.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (showApps) {
                val apps = (s.seenApps.entries.map { it.key to it.value } +
                    s.blocked.filter { it !in s.seenApps }.map { it to it })
                    .sortedBy { it.second.lowercase() }
                items(apps, key = { it.first }) { (pkg, label) ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(label)
                            Text(pkg, style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = pkg !in s.blocked, onCheckedChange = { Prefs.setBlocked(pkg, !it) })
                    }
                }
            }

            item {
                Section("Log", trailing = {
                    Row {
                        TextButton(onClick = { Prefs.clearLog() }) { Text("Clear") }
                        TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "Hide" else "Show") }
                    }
                }) {
                    if (showLog) {
                        if (s.log.isEmpty()) Text("Nothing yet.", style = MaterialTheme.typography.bodySmall)
                        s.log.forEach {
                            Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun Section(title: String, trailing: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                trailing?.invoke()
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun StatusRow(ok: Boolean, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Icon(
            if (ok) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

private fun isListenerEnabled(ctx: android.content.Context): Boolean {
    val flat = SysSettings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
    val me = ComponentName(ctx, NotificationForwarder::class.java)
    return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
}
