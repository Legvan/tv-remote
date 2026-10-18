package com.porter.tvremote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Launcher activity — shows server status (URL, ADB state) and Start/Stop buttons.
 *
 * Designed for D-pad navigation on Android TV (no touch or keyboard required: the HTTP port
 * has −/+ buttons next to its text field).
 * Status updates arrive via LocalBroadcast from RemoteService.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvUrl: TextView
    private lateinit var tvAdbStatus: TextView
    private lateinit var statusDot: View
    private lateinit var adbDot: View
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var editHttpPort: EditText
    private lateinit var btnPortDown: Button
    private lateinit var btnPortUp: Button

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != RemoteService.ACTION_STATUS_UPDATE) return
            showServiceStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        applyWindowInsets()

        tvStatus    = findViewById(R.id.tvStatus)
        tvUrl       = findViewById(R.id.tvUrl)
        tvAdbStatus = findViewById(R.id.tvAdbStatus)
        statusDot   = findViewById(R.id.statusDot)
        adbDot      = findViewById(R.id.adbDot)
        btnStart    = findViewById(R.id.btnStart)
        btnStop     = findViewById(R.id.btnStop)
        editHttpPort = findViewById(R.id.editHttpPort)
        btnPortDown  = findViewById(R.id.btnPortDown)
        btnPortUp    = findViewById(R.id.btnPortUp)
        editHttpPort.setText(HttpServerSettings.load(this).toString())

        btnStart.setOnClickListener { startServer() }
        btnStop.setOnClickListener  { stopServer()  }
        btnPortDown.setOnClickListener { stepPort(-1) }
        btnPortUp.setOnClickListener   { stepPort(+1) }
        editHttpPort.setOnKeyListener { _, keyCode, event -> jumpToPortStepper(keyCode, event) }

        // Default focus to Start button on TV D-pad
        btnStart.requestFocus()
    }

    /**
     * Edge-to-edge is mandatory from targetSdk 36 — windowOptOutEdgeToEdgeEnforcement is ignored.
     * The layout's own padding stays the design baseline; system bar and cutout insets are added
     * on top of it so nothing hides behind the status/navigation bars on phones. TVs report zero
     * insets, so the TV layout is unchanged.
     */
    private fun applyWindowInsets() {
        val root = findViewById<View>(R.id.root)
        val basePadding = root.paddingLeft
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(
                basePadding + bars.left,
                basePadding + bars.top,
                basePadding + bars.right,
                basePadding + bars.bottom
            )
            windowInsets
        }
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(
            this,
            statusReceiver,
            IntentFilter(RemoteService.ACTION_STATUS_UPDATE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // Re-sync UI from the in-process snapshot in case we missed a broadcast while paused
        // (e.g. the "Allow USB Debugging?" dialog triggers onPause/onResume mid-startup).
        showServiceStatus()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(statusReceiver)
    }

    // ─── HTTP port ───────────────────────────────────────────────────────────

    private fun stepPort(delta: Int) {
        val port = HttpServerSettings.stepPort(editHttpPort.text.toString(), delta)
        editHttpPort.setText(port.toString())
        editHttpPort.error = null
    }

    /**
     * Remote left/right on the port field goes straight to −/+. Left alone, the first press
     * would only collapse the select-all-on-focus highlight and look like nothing happened.
     * Full keyboards (and phone IMEs) keep normal cursor movement.
     */
    private fun jumpToPortStepper(keyCode: Int, event: KeyEvent): Boolean {
        val target = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT  -> btnPortDown
            KeyEvent.KEYCODE_DPAD_RIGHT -> btnPortUp
            else -> return false
        }
        if (event.device?.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC) return false
        return event.action == KeyEvent.ACTION_DOWN && target.requestFocus()
    }

    // ─── Service control ─────────────────────────────────────────────────────

    private fun startServer() {
        // Already running, e.g. started by BootReceiver before this screen got a status. The
        // server keeps the port it was started with, so do not save one it would not use.
        if (RemoteService.currentStatus != null) {
            showServiceStatus()
            return
        }
        val port = HttpServerSettings.parsePort(editHttpPort.text.toString())
        if (port == null) {
            editHttpPort.error = getString(R.string.http_port_invalid)
            editHttpPort.requestFocus()
            return
        }
        HttpServerSettings.save(this, port)
        ContextCompat.startForegroundService(this, Intent(this, RemoteService::class.java))
        offerBatteryOptimizationExemption()
        updateUi(running = true, adbOk = false, url = null, starting = true)
        btnStart.isEnabled = false
        btnStop.isEnabled  = true
        btnStop.requestFocus()
    }

    private fun stopServer() {
        stopService(Intent(this, RemoteService::class.java))
        updateUi(running = false, adbOk = false, url = null)
        btnStart.isEnabled = true
        btnStop.isEnabled  = false
        btnStart.requestFocus()
    }

    /**
     * Shield app-idle policy otherwise stops the UDP listener when the TV enters standby.
     *
     * We open the battery-optimization list and let the user exempt the app there.
     * The direct "allow?" dialog (ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) would be
     * one tap shorter, but it needs REQUEST_IGNORE_BATTERY_OPTIMIZATIONS — a permission
     * Google Play restricts to a short list of app categories a TV remote is not on.
     *
     * Shown once. Nagging on every Start Server press would be worse than the problem.
     */
    private fun offerBatteryOptimizationExemption() {
        val powerManager = getSystemService(PowerManager::class.java)
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) return
        if (!BatteryOptimizationPrompt.pending(this)) return

        runCatching {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }.onSuccess {
            BatteryOptimizationPrompt.markShown(this)
        }.onFailure {
            // Some TV builds ship no battery-optimization screen at all. Nothing to do.
            BatteryOptimizationPrompt.markShown(this)
        }
    }

    // ─── UI updates ──────────────────────────────────────────────────────────

    /** Shows what RemoteService last published; null means it is not running. */
    private fun showServiceStatus() {
        val status = RemoteService.currentStatus
        if (status == null) {
            updateUi(running = false, adbOk = false, url = null)
        } else {
            // The field is locked while running. Show the port the service was started with,
            // not an edit made while this screen still showed it as stopped.
            editHttpPort.setText(HttpServerSettings.load(this).toString())
            updateUi(status.serverRunning, status.adbConnected, status.url, status.starting)
        }
    }

    private fun updateUi(running: Boolean, adbOk: Boolean, url: String?, starting: Boolean = false) {
        if (running) {
            // While starting, the URL shows up as soon as HTTP is listening; ADB can take longer.
            tvStatus.text = getString(
                if (starting && url == null) R.string.status_starting else R.string.status_running
            )
            statusDot.setBackgroundResource(R.drawable.shape_status_dot)
            statusDot.background.setTint(Color.parseColor("#34C759"))
            tvUrl.text = when {
                url != null -> url
                starting    -> "—"
                else        -> getString(R.string.http_unavailable, HttpServerSettings.load(this))
            }
            btnStart.isEnabled = false
            btnStop.isEnabled  = true
            setPortEditable(false)
        } else {
            tvStatus.text = getString(R.string.status_stopped)
            statusDot.setBackgroundResource(R.drawable.shape_status_dot)
            statusDot.background.setTint(Color.parseColor("#444444"))
            tvUrl.text    = "—"
            btnStart.isEnabled = true
            btnStop.isEnabled  = false
            setPortEditable(true)
        }

        tvAdbStatus.text = when {
            adbOk    -> "ADB: connected (loopback 127.0.0.1:5555)"
            starting -> "ADB: connecting — accept \"Allow USB debugging?\" if asked"
            running  -> "ADB: not connected — open Developer Options → Enable USB Debugging"
            else     -> "ADB: not connected — start the server first"
        }
        val adbColor = if (adbOk) "#34C759" else "#6B7280"
        tvAdbStatus.setTextColor(Color.parseColor(adbColor))
        adbDot.setBackgroundResource(R.drawable.shape_status_dot)
        adbDot.background.setTint(Color.parseColor(adbColor))
    }

    /** The running server keeps the port it was started with, so it is only editable while stopped. */
    private fun setPortEditable(editable: Boolean) {
        editHttpPort.isEnabled = editable
        btnPortDown.isEnabled  = editable
        btnPortUp.isEnabled    = editable
    }
}

/** Remembers that the user was sent to the battery-optimization screen once already. */
private object BatteryOptimizationPrompt {
    private const val KEY = "battery_prompt_shown"

    fun pending(context: Context): Boolean = !Preferences.of(context).getBoolean(KEY, false)

    fun markShown(context: Context) {
        Preferences.of(context).edit().putBoolean(KEY, true).apply()
    }
}
