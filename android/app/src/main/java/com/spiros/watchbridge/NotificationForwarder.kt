package com.spiros.watchbridge

import android.app.Notification
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Receives every notification posted on the phone and forwards the ones worth seeing on the wrist. */
class NotificationForwarder : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recent = LinkedHashMap<String, Pair<Int, Long>>()
    private val labels = HashMap<String, String>()

    override fun onListenerConnected() {
        Prefs.init(this)
        Prefs.setListenerConnected(true)
        Prefs.log("notification listener connected")
    }

    override fun onListenerDisconnected() {
        Prefs.init(this)
        Prefs.setListenerConnected(false)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        Prefs.init(this)
        val s = Prefs.state.value
        if (!s.forwarding) return
        if (sbn.packageName == packageName) return
        if (sbn.isOngoing) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return
        if (s.blocked.contains(sbn.packageName)) {
            noteApp(sbn.packageName)
            return
        }

        rankingMap?.let { map ->
            val r = Ranking()
            if (map.getRanking(sbn.key, r)) {
                val importance = r.channel?.importance ?: NotificationManager.IMPORTANCE_DEFAULT
                if (importance < NotificationManager.IMPORTANCE_DEFAULT) return
                if (r.isAmbient) return
            }
        }

        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return

        val hash = (title + "\u0000" + text).hashCode()
        val now = System.currentTimeMillis()
        synchronized(recent) {
            val prev = recent[sbn.key]
            if (prev != null && prev.first == hash && now - prev.second < 10 * 60_000L) return
            recent[sbn.key] = hash to now
            while (recent.size > 300) recent.remove(recent.keys.first())
        }

        val appName = noteApp(sbn.packageName)
        scope.launch { ApnsClient.forward(appName, title, text, sbn.packageName, sbn.key) }
    }

    private fun noteApp(pkg: String): String {
        val label = labels.getOrPut(pkg) {
            try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
            } catch (_: PackageManager.NameNotFoundException) {
                pkg
            }
        }
        Prefs.noteApp(pkg, label)
        return label
    }
}
