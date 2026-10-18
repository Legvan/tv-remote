package com.porter.tvremote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service that keeps the HTTP server and UDP starter alive indefinitely.
 *
 * Lifecycle:
 *   START_SERVICE → onCreate() → startForeground() → HTTP/UDP start + ADB connect
 *   STOP_SERVICE  → onDestroy() → UDP stop + lock release; HTTP stop + ADB disconnect
 *                   finish in the background, and the next start waits for the HTTP port
 *
 * Broadcasts:
 *   ACTION_STATUS_UPDATE — sent when ADB / server state changes; received by MainActivity.
 */
class RemoteService : Service() {

    companion object {
        private const val TAG = "RemoteService"
        private const val NOTIF_CHANNEL_ID = "tv_remote_server"
        const val NOTIFICATION_ID = 1001

        const val ACTION_STATUS_UPDATE = "com.porter.tvremote.STATUS_UPDATE"
        const val EXTRA_SERVER_RUNNING = "server_running"
        const val EXTRA_ADB_CONNECTED  = "adb_connected"
        const val EXTRA_SERVER_URL     = "server_url"

        /**
         * Last-known status snapshot, kept in-process so MainActivity can re-sync
         * in onResume() after missing a broadcast (e.g. the "Allow USB Debugging?"
         * dialog causes onPause/onResume while the service is still starting).
         * Null when the service is not running.
         */
        data class StatusSnapshot(
            val serverRunning: Boolean,
            val adbConnected: Boolean,
            val url: String?
        )
        @Volatile var currentStatus: StatusSnapshot? = null
            private set

        /**
         * Stopping blocks: Ktor's graceful shutdown, and an ADB connect that may be waiting on
         * "Allow USB debugging?". onDestroy hands it to this scope, which outlives the instance.
         */
        private val teardownScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        /** The previous instance's HTTP shutdown; the next start waits for it to free the port. */
        @Volatile private var pendingHttpStop: Job? = null
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var adb: AdbController
    private lateinit var httpServer: HttpServer
    private lateinit var yatseStarter: YatseStarter

    private var wakeLock:  PowerManager.WakeLock? = null
    private var wifiLock:  WifiManager.WifiLock? = null

    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service creating")

        adb        = AdbController(this)
        httpServer = HttpServer(this, adb, HttpServerSettings.load(this))
        val kodiAction = wakeAndLaunchKodiAction()
        yatseStarter = YatseStarter(serviceScope) { kodiAction.run() }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting…", null))
        acquireLocks()
        // Independent from HTTP startup so a UDP bind failure cannot disable the web remote,
        // and an HTTP startup failure cannot prevent Yatse/Kore compatibility.
        yatseStarter.start()
        startServerAsync()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Already started — nothing extra to do; service is sticky
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "Service destroying")
        currentStatus = null
        // Cancelled first, so a start still waiting on ADB cannot publish a status afterwards.
        serviceScope.cancel()
        yatseStarter.stop()
        // Both block, the ADB one for as long as an authorization prompt stays unanswered.
        // Called here on the main thread, that was an ANR.
        pendingHttpStop = teardownScope.launch { httpServer.stop() }
        teardownScope.launch { adb.disconnect() }
        releaseLocks()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─── Server start ─────────────────────────────────────────────────────────

    private fun startServerAsync() {
        serviceScope.launch {
            // A Stop moments ago may still be releasing the port in the background.
            pendingHttpStop?.join()

            // 1. Start HTTP server (non-blocking, Ktor starts its own coroutines)
            var httpRunning = false
            try {
                httpServer.start()
                httpRunning = true
                Log.i(TAG, "HTTP server running on port ${httpServer.port}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start HTTP server", e)
            }

            // 2. Connect to ADB loopback even when HTTP is unavailable. The UDP
            // listener is independent and still needs the fixed wake/Kodi action.
            val adbOk = adb.connect()
            Log.i(TAG, "ADB loopback connected: $adbOk")

            // 3. Get LAN IP for notification / broadcast
            val ip = getLanIp()
            val url = if (httpRunning) "http://$ip:${httpServer.port}" else null

            // 4. Update notification and broadcast to MainActivity
            val notificationText = if (httpRunning) {
                "Running on $ip:${httpServer.port}"
            } else {
                "Yatse/Kore starter on UDP ${YatseStarter.DEFAULT_PORT}; HTTP port ${httpServer.port} unavailable"
            }
            withContext(Dispatchers.Main) {
                // onDestroy runs on the main thread too. If it already has, Stop was pressed
                // while ADB was connecting: no notification or "running" status after that.
                ensureActive()
                updateNotification(notificationText, adbOk)
                // The foreground service and UDP listener are running even if HTTP could not bind.
                // Report the service lifecycle accurately; a null URL communicates HTTP failure.
                broadcastStatus(serverRunning = true, adbConnected = adbOk, url = url)
            }
        }
    }

    /**
     * The one action the Yatse/Kore listener can invoke. It lives here rather than in
     * AdbController so that class stays a generic ADB wrapper with no favourite app.
     */
    private fun wakeAndLaunchKodiAction() = WakeAndLaunchKodiAction(
        wake = { adb.keyEvent(AdbController.KEYCODE_WAKEUP) },
        launchKodi = { adb.launchApp(AdbController.KODI_ACTIVITY) },
        isKodiForeground = { adb.currentApp() == AdbController.KODI_PACKAGE },
        onWakeFailure = { Log.w(TAG, "ADB wake failed; continuing after hardware wake", it) },
    )

    // ─── Notifications ────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIF_CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(contentText: String, adbOk: Boolean?): Notification {
        val activityIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val adbLine = when (adbOk) {
            true  -> "ADB connected"
            false -> "ADB not connected — open app for setup help"
            null  -> ""
        }
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(contentText)
            .setSubText(adbLine.takeIf { it.isNotEmpty() })
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(contentText: String, adbOk: Boolean) {
        val notif = buildNotification(contentText, adbOk)
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notif)
    }

    // ─── Broadcasts ───────────────────────────────────────────────────────────

    private fun broadcastStatus(serverRunning: Boolean, adbConnected: Boolean, url: String?) {
        currentStatus = StatusSnapshot(serverRunning, adbConnected, url)
        // Explicit package: MainActivity's receiver is not exported, and an implicit broadcast
        // would also reach any other app that registered for this action.
        sendBroadcast(Intent(ACTION_STATUS_UPDATE).setPackage(packageName).apply {
            putExtra(EXTRA_SERVER_RUNNING, serverRunning)
            putExtra(EXTRA_ADB_CONNECTED,  adbConnected)
            putExtra(EXTRA_SERVER_URL,     url)
        })
    }

    // ─── WakeLock / WifiLock ─────────────────────────────────────────────────

    private fun acquireLocks() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TVRemote::ServerLock").apply {
            @Suppress("WakelockTimeout")
            acquire()
        }
        val wm = applicationContext.getSystemService(WifiManager::class.java)
        // WIFI_MODE_FULL_LOW_LATENCY is the non-deprecated replacement (API 29+); minSdk is 26
        // but the TV runs API 30, so this branch always executes in practice.
        val wifiLockMode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q)
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        else
            @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = wm.createWifiLock(wifiLockMode, "TVRemote::WifiLock").apply {
            acquire()
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun getLanIp(): String {
        return try {
            java.net.NetworkInterface.getNetworkInterfaces()
                .asSequence()
                .filter { !it.isLoopback && it.isUp }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<java.net.Inet4Address>()
                .firstOrNull()
                ?.hostAddress ?: "unknown"
        } catch (_: Exception) { "unknown" }
    }
}
