package com.porter.tvremote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Launcher activity — shows server status (URL, ADB state) and Start/Stop buttons.
 *
 * Designed for D-pad navigation on Android TV (two focusable buttons, no touch required).
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

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != RemoteService.ACTION_STATUS_UPDATE) return
            val running = intent.getBooleanExtra(RemoteService.EXTRA_SERVER_RUNNING, false)
            val adbOk   = intent.getBooleanExtra(RemoteService.EXTRA_ADB_CONNECTED,  false)
            val url     = intent.getStringExtra(RemoteService.EXTRA_SERVER_URL)
            updateUi(running, adbOk, url)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus    = findViewById(R.id.tvStatus)
        tvUrl       = findViewById(R.id.tvUrl)
        tvAdbStatus = findViewById(R.id.tvAdbStatus)
        statusDot   = findViewById(R.id.statusDot)
        adbDot      = findViewById(R.id.adbDot)
        btnStart    = findViewById(R.id.btnStart)
        btnStop     = findViewById(R.id.btnStop)

        btnStart.setOnClickListener { startServer() }
        btnStop.setOnClickListener  { stopServer()  }

        // Default focus to Start button on TV D-pad
        btnStart.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(
            this,
            statusReceiver,
            IntentFilter(RemoteService.ACTION_STATUS_UPDATE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(statusReceiver)
    }

    // ─── Service control ─────────────────────────────────────────────────────

    private fun startServer() {
        ContextCompat.startForegroundService(this, Intent(this, RemoteService::class.java))
        updateUi(running = true, adbOk = false, url = null)
        btnStart.isEnabled = false
        btnStop.isEnabled  = true
        tvStatus.text      = getString(R.string.status_starting)
        btnStop.requestFocus()
    }

    private fun stopServer() {
        stopService(Intent(this, RemoteService::class.java))
        updateUi(running = false, adbOk = false, url = null)
        btnStart.isEnabled = true
        btnStop.isEnabled  = false
        btnStart.requestFocus()
    }

    // ─── UI updates ──────────────────────────────────────────────────────────

    private fun updateUi(running: Boolean, adbOk: Boolean, url: String?) {
        if (running) {
            tvStatus.text = getString(R.string.status_running)
            statusDot.setBackgroundResource(R.drawable.shape_status_dot)
            statusDot.background.setTint(Color.parseColor("#34C759"))
            tvUrl.text    = url ?: "http://<TV-IP>:8080"
            btnStart.isEnabled = false
            btnStop.isEnabled  = true
        } else {
            tvStatus.text = getString(R.string.status_stopped)
            statusDot.setBackgroundResource(R.drawable.shape_status_dot)
            statusDot.background.setTint(Color.parseColor("#444444"))
            tvUrl.text    = "—"
            btnStart.isEnabled = true
            btnStop.isEnabled  = false
        }

        tvAdbStatus.text = if (adbOk) {
            "ADB: connected (loopback 127.0.0.1:5555)"
        } else {
            "ADB: not connected — " +
            if (running) "open Developer Options → Enable USB Debugging"
            else "start the server first"
        }
        val adbColor = if (adbOk) "#34C759" else "#6B7280"
        tvAdbStatus.setTextColor(Color.parseColor(adbColor))
        adbDot.setBackgroundResource(R.drawable.shape_status_dot)
        adbDot.background.setTint(Color.parseColor(adbColor))
    }
}
