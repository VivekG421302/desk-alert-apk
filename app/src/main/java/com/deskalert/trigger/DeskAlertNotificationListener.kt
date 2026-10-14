package com.deskalert.trigger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Listens for notifications posted on THIS device and forwards them to the
 * owner's own backend, so they can see their phone's notifications while the
 * phone itself is out of reach (e.g. held at a front desk).
 *
 * This only ever sees notifications that already appear in this phone's own
 * status bar - nothing is read from any other device.
 */
class DeskAlertNotificationListener : NotificationListenerService() {

    private val io = Executors.newSingleThreadExecutor()
    private val endpoint = "https://desk-alert-backend.vercel.app/api/noti"

    companion object {
        const val CHANNEL_ID = "desk_alert_running"
        const val FOREGROUND_ID = 1001

        // Packages whose notifications are just noise - skip these.
        private val IGNORED_PACKAGES = setOf(
            "com.deskalert.trigger",
            "android"
        )
    }

    // --- Lifecycle: promote to a foreground service as soon as we're connected ---

    override fun onListenerConnected() {
        super.onListenerConnected()
        startForeground(FOREGROUND_ID, buildPersistentNotification())
    }

    override fun onCreate() {
        super.onCreate()
        createChannelIfNeeded()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    // --- Capture ---

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        if (pkg in IGNORED_PACKAGES) return

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()

        if (title.isEmpty() && text.isEmpty()) return

        val type = classify(pkg, title, text)
        val appLabel = appLabelFor(pkg)

        forward(type = type, appName = appLabel, title = title, message = text)
    }

    /** Best-effort classification so the backend/UI can show a call vs. a message differently. */
    private fun classify(pkg: String, title: String, text: String): String {
        val isDialer = pkg.contains("dialer") || pkg.contains("incallui") || pkg == "com.google.android.dialer"
        val looksLikeCall = title.contains("call", ignoreCase = true) ||
            text.contains("calling", ignoreCase = true) ||
            text.contains("incoming call", ignoreCase = true)
        return if (isDialer || looksLikeCall) "CALL" else "MESSAGE"
    }

    private fun appLabelFor(pkg: String): String = try {
        val pm = applicationContext.packageManager
        val appInfo = pm.getApplicationInfo(pkg, 0)
        pm.getApplicationLabel(appInfo).toString()
    } catch (e: Exception) {
        pkg
    }

    // --- Forwarding (same backend contract as the manual buttons in MainActivity) ---

    private fun forward(type: String, appName: String, title: String, message: String) {
        val prefs = getSharedPreferences("desk_alert", Context.MODE_PRIVATE)
        val password = prefs.getString("password", null)
        if (password.isNullOrBlank()) return // not configured yet; nothing to send to

        io.execute {
            try {
                val body = JSONObject()
                    .put("secret", password)
                    .put("appName", appName)
                    .put("title", title)
                    .put("message", message)
                    .put("type", type)
                    .toString()

                val conn = URL(endpoint).openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 10_000
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    conn.responseCode // trigger the request; ignore result, nothing to show here
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                // Silent failure is fine here - this runs unattended. A future
                // improvement could log failures and retry.
            }
        }
    }

    // --- Persistent foreground notification ---

    private fun buildPersistentNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Desk Alert is running")
            .setContentText("Forwarding your notifications to your server")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Desk Alert status",
                NotificationManager.IMPORTANCE_LOW // low importance = no sound/heads-up, just sits in the shade
            )
            channel.description = "Shows that Desk Alert is actively forwarding notifications"
            manager.createNotificationChannel(channel)
        }
    }
}
