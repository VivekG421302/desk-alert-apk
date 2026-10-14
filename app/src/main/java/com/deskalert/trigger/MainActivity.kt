package com.deskalert.trigger

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val io = Executors.newSingleThreadExecutor()
    private val endpoint = "https://desk-alert-backend.vercel.app/api/noti"

    private lateinit var passwordInput: EditText
    private lateinit var sendButton: Button
    private lateinit var callButton: Button
    private lateinit var statusText: TextView
    private lateinit var grantAccessButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        passwordInput = findViewById(R.id.passwordInput)
        sendButton = findViewById(R.id.sendButton)
        callButton = findViewById(R.id.callButton)
        statusText = findViewById(R.id.statusText)
        grantAccessButton = findViewById(R.id.grantAccessButton)

        val prefs = getSharedPreferences("desk_alert", Context.MODE_PRIVATE)
        passwordInput.setText(prefs.getString("password", ""))

        passwordInput.doAfterTextChangedSimple { text ->
            prefs.edit().putString("password", text).apply()
        }

        sendButton.setOnClickListener { send("MESSAGE", "Alert from phone", "Triggered manually") }
        callButton.setOnClickListener { send("CALL", "Incoming call", "Triggered manually") }

        grantAccessButton.setOnClickListener { openNotificationAccessSettings() }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        if (isNotificationAccessGranted()) {
            statusText.text = "Notification access: ON — Desk Alert is actively mirroring notifications."
            grantAccessButton.visibility = android.view.View.GONE
        } else {
            statusText.text = "Notification access: OFF — tap below to turn on automatic mirroring."
            grantAccessButton.visibility = android.view.View.VISIBLE
        }
    }

    /** Checks whether the user has granted this app Notification Access. */
    private fun isNotificationAccessGranted(): Boolean {
        val enabledListeners = NotificationManagerCompat.getEnabledListenerPackages(this)
        return packageName in enabledListeners
    }

    /** Sends the user straight to the system screen where they can flip Desk Alert on. */
    private fun openNotificationAccessSettings() {
        toast("Find and enable \"Desk Alert\" in the list")
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    private fun send(type: String, title: String, message: String) {
        val password = passwordInput.text.toString().trim()
        if (password.isEmpty()) {
            toast("Enter your app password")
            return
        }
        setBusy(true)

        io.execute {
            val result = try {
                post(password, type, title, message)
            } catch (e: Exception) {
                "Failed: ${e.javaClass.simpleName} (check connection)"
            }
            runOnUiThread {
                setBusy(false)
                if (result == null) {
                    getSharedPreferences("desk_alert", Context.MODE_PRIVATE)
                        .edit().putString("password", password).apply()
                    toast("Alert sent ✓")
                } else {
                    toast(result)
                }
            }
        }
    }

    /** Returns null on success, or an error message. */
    private fun post(password: String, type: String, title: String, message: String): String? {
        // The backend expects the key "secret"
        val body = JSONObject()
            .put("secret", password)
            .put("appName", "Desk Alert App")
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

            return when (val code = conn.responseCode) {
                in 200..299 -> null
                401 -> "Wrong password"
                else -> "Server error ($code)"
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun setBusy(busy: Boolean) {
        sendButton.isEnabled = !busy
        callButton.isEnabled = !busy
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }
}

/** Small helper so we don't need a full TextWatcher boilerplate inline above. */
private fun EditText.doAfterTextChangedSimple(action: (String) -> Unit) {
    this.addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) {
            action(s?.toString().orEmpty())
        }
    })
}
