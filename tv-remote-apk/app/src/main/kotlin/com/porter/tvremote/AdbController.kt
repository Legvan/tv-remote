package com.porter.tvremote

import android.content.Context
import android.util.Base64
import android.util.Log
import com.cgutman.adblib.AdbBase64
import com.cgutman.adblib.AdbConnection
import com.cgutman.adblib.AdbCrypto
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages the loopback ADB connection to the TV's own ADB daemon at 127.0.0.1:5555.
 *
 * Architecture: instead of INJECT_EVENTS (requires system signature), we connect back
 * to the device's ADB daemon via the ADB wire protocol (cgutman/AdbLib).
 * The daemon grants us `shell` privilege — sufficient for input keyevent, am start, dumpsys.
 *
 * First run: once debugging is on, the TV shows "Allow USB debugging?" and the user must
 * approve it once; [connect] waits up to [AUTH_TIMEOUT_MS] for that.
 * The RSA key pair is saved in app-private storage and reused on subsequent runs.
 */
class AdbController(private val context: Context) {

    /** How a [connect] attempt ended. */
    enum class ConnectResult {
        CONNECTED,

        /**
         * "Allow USB debugging?" was not accepted within [AUTH_TIMEOUT_MS]: closed with Back,
         * cancelled, or not answered. adbd does not ask again on the same connection.
         */
        NOT_AUTHORIZED,

        /** No ADB daemon listening (debugging off), or the connection failed or was closed. */
        FAILED,
    }

    companion object {
        private const val TAG = "AdbController"
        const val ADB_HOST = "127.0.0.1"
        const val ADB_PORT = 5555
        const val KEYCODE_WAKEUP = 224
        private const val RECONNECT_DELAY_MS = 250L

        /**
         * How long [connect] waits for "Allow USB debugging?" to be accepted. Long enough to
         * read the prompt, tick "Always allow" and press Allow with a remote. A key the TV
         * already trusts connects at once, so only an open or dismissed prompt reaches it.
         */
        const val AUTH_TIMEOUT_MS = 60_000L
        const val KODI_PACKAGE = "org.xbmc.kodi"
        const val KODI_ACTIVITY = "org.xbmc.kodi/.Splash"

        /** Maps URL-friendly names to Android activity strings (mirrors Python APPS dict). */
        val APPS = mapOf(
            "youtube"  to "com.google.android.youtube.tv/com.google.android.apps.youtube.tv.activity.ShellActivity",
            "netflix"  to "com.netflix.ninja/.MainActivity",
            "prime"    to "com.amazon.amazonvideo.livingroom/.MainActivity",
            "disney"   to "com.disney.disneyplus/.MainActivity",
            "settings" to "com.android.settings/.Settings",
            "spotify"  to "com.spotify.tv.android/.SpotifyTVActivity",
            "kodi"     to KODI_ACTIVITY,
        )

        /** AdbLib Base64 adapter using Android's built-in Base64 codec. */
        val base64: AdbBase64 = AdbBase64 { data ->
            Base64.encodeToString(data, Base64.NO_WRAP)
        }

        private const val NOT_AUTHORIZED_MESSAGE =
            "ADB not authorized — on the TV press Stop Server, then Start Server, and accept \"Allow USB debugging?\""

        /** One daemon thread for the whole process; it only ever closes a socket. */
        private val authTimeouts: ScheduledExecutorService by lazy {
            ScheduledThreadPoolExecutor(1) { task ->
                Thread(task, "adb-auth-timeout").apply { isDaemon = true }
            }.apply { removeOnCancelPolicy = true }
        }
    }

    @Volatile private var connection: AdbConnection? = null
    private val lock = Any()

    /**
     * Socket of a [connect] in progress. AdbLib's connect() waits for the user to answer
     * "Allow USB debugging?" for as long as that takes, holding [lock]; [disconnect] closes
     * this socket to make it give up instead of queueing behind it.
     */
    @Volatile private var pendingSocket: Socket? = null

    /** Set by [disconnect]. A controller is not reused after that. */
    @Volatile private var closed = false

    /**
     * Set once a [connect] ran out of time on "Allow USB debugging?". [shell] then fails at
     * once instead of reconnecting: every new connection with a key the TV does not trust
     * asks for another prompt, and the web remote polls every few seconds. adbd queues those
     * behind an open one and shows them later, even for connections long gone. A new start of
     * the service (Stop Server, Start Server) gets a new controller, which asks once again.
     */
    @Volatile private var authTimedOut = false

    private val authDeadline = ConnectDeadline(AUTH_TIMEOUT_MS, authTimeouts)

    private val keyFile    = File(context.filesDir, "adbkey")
    private val pubKeyFile = File(context.filesDir, "adbkey.pub")

    private val crypto: AdbCrypto by lazy { loadOrGenerateCrypto() }

    private fun loadOrGenerateCrypto(): AdbCrypto {
        return if (keyFile.exists() && pubKeyFile.exists()) {
            try {
                AdbCrypto.loadAdbKeyPair(base64, keyFile, pubKeyFile)
                    .also { Log.i(TAG, "ADB key pair loaded from storage") }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load ADB key pair, regenerating", e)
                generateAndSaveCrypto()
            }
        } else {
            generateAndSaveCrypto()
        }
    }

    private fun generateAndSaveCrypto(): AdbCrypto {
        Log.i(TAG, "Generating new ADB RSA key pair")
        return AdbCrypto.generateAdbKeyPair(base64).also { kp ->
            kp.saveAdbKeyPair(keyFile, pubKeyFile)
            Log.i(TAG, "ADB key pair saved to ${context.filesDir}")
        }
    }

    /** Connect to the loopback ADB daemon. */
    fun connect(): ConnectResult {
        synchronized(lock) {
            if (closed) return ConnectResult.FAILED
            val socket = Socket()
            pendingSocket = socket
            return try {
                // Checked after publishing the socket, so a concurrent disconnect() either
                // sees the socket and closes it, or this sees the flag.
                if (closed) throw IOException("ADB controller closed")
                socket.connect(InetSocketAddress(ADB_HOST, ADB_PORT))
                val conn   = AdbConnection.create(socket, crypto)
                // connect() blocks until authenticated: for a key the TV does not trust yet,
                // until "Allow USB debugging?" is accepted, which may never happen.
                authDeadline.run(socket) { conn.connect() }
                connection = conn
                Log.i(TAG, "ADB loopback connected ($ADB_HOST:$ADB_PORT)")
                ConnectResult.CONNECTED
            } catch (e: ConnectDeadline.Expired) {
                Log.w(TAG, "ADB connect: \"Allow USB debugging?\" not accepted within ${AUTH_TIMEOUT_MS / 1000} s")
                authTimedOut = true
                runCatching { socket.close() }
                connection = null
                ConnectResult.NOT_AUTHORIZED
            } catch (e: Exception) {
                // disconnect() closes the socket of a connect still waiting: that is Stop, not a failure.
                if (closed) Log.i(TAG, "ADB connect cancelled") else Log.e(TAG, "ADB connect failed: ${e.message}")
                runCatching { socket.close() }
                connection = null
                ConnectResult.FAILED
            } finally {
                pendingSocket = null
            }
        }
    }

    /**
     * Execute a shell command and return its stdout+stderr output.
     * Auto-reconnects once on failure.
     */
    fun shell(cmd: String): String {
        synchronized(lock) {
            for (attempt in 0..1) {
                var conn = connection
                if (conn == null) {
                    if (authTimedOut) throw IllegalStateException(NOT_AUTHORIZED_MESSAGE)
                    if (connect() != ConnectResult.CONNECTED) {
                        if (authTimedOut) throw IllegalStateException(NOT_AUTHORIZED_MESSAGE)
                        if (attempt == 1) {
                            throw IllegalStateException(
                                "ADB not connected — enable ADB over network in Developer Options"
                            )
                        }
                        Thread.sleep(RECONNECT_DELAY_MS)
                        continue
                    }
                    conn = connection ?: throw IllegalStateException("ADB connection was not retained")
                }
                val activeConnection = conn
                    ?: throw IllegalStateException("ADB connection was not retained")
                return try {
                    execShell(activeConnection, cmd)
                } catch (e: Exception) {
                    Log.w(TAG, "Shell error (attempt $attempt): ${e.message}")
                    connection = null
                    if (attempt == 1) throw e
                    Thread.sleep(RECONNECT_DELAY_MS)
                    continue
                }
            }
            throw IllegalStateException("Unreachable")
        }
    }

    private fun execShell(conn: AdbConnection, cmd: String): String {
        val stream = conn.open("shell:$cmd")
        val sb = StringBuilder()
        try {
            while (!stream.isClosed) {
                val bytes = try { stream.read() } catch (_: Exception) { break }
                    ?: break
                sb.append(String(bytes))
            }
        } finally {
            runCatching { stream.close() }
        }
        return sb.toString().trim()
    }

    // ─── High-level TV control methods (mirror Python TVClient) ──────────────

    fun keyEvent(code: Int) = shell("input keyevent $code")

    fun launchApp(activity: String) = shell("am start -n $activity")

    fun goHome() = shell("am start -a android.intent.action.MAIN -c android.intent.category.HOME")

    fun launchAssistant() {
        try {
            val result = shell(
                "am start -n com.google.android.googlequicksearchbox" +
                "/com.google.android.googlequicksearchbox.VoiceSearchActivity"
            )
            if ("Error" in result || "Exception" in result) keyEvent(231)
        } catch (e: Exception) {
            try { keyEvent(231) } catch (_: Exception) { throw e }
        }
    }

    fun screenState(): String {
        val result = shell("dumpsys power | grep 'Display Power'")
        return if ("state=ON" in result) "ON" else "OFF"
    }

    fun currentApp(): String {
        val result = shell("dumpsys activity activities | grep mResumedActivity")
        return try {
            result.split("u0 ").last().trim().split("/")[0]
        } catch (_: Exception) {
            result.trim()
        }
    }

    /**
     * Send text to the currently focused input field using ANSI-C quoting so that
     * all ASCII special characters (quotes, $, &, ;, etc.) pass through safely.
     * Unicode > 127 is silently skipped (Android `input text` limitation).
     */
    fun sendText(text: String) {
        val parts = text.map { ch ->
            when {
                ch == ' '      -> "%s"
                ch.code < 128  -> "\\x${ch.code.toString(16).padStart(2, '0')}"
                else           -> ""
            }
        }
        val escaped = "\$'${parts.joinToString("")}'"
        shell("input text $escaped")
    }

    /**
     * Closes the connection for good, including one still waiting for authorization.
     * May still wait for a shell command in flight, so keep it off the main thread.
     */
    fun disconnect() {
        closed = true
        pendingSocket?.let { runCatching { it.close() } }
        synchronized(lock) {
            runCatching { connection?.close() }
            connection = null
        }
    }

    val isConnected: Boolean get() = connection != null
}

/**
 * Puts a time limit on AdbLib's AdbConnection.connect(), which waits for "Allow USB debugging?"
 * for as long as that takes. adbd shows the prompt once per connection and never tells the
 * client that it was closed with Back or cancelled: the connection just stays unauthenticated.
 * Closing the socket is what makes connect() give up, the same way [AdbController.disconnect]
 * stops it.
 */
internal class ConnectDeadline(
    private val timeoutMs: Long,
    private val scheduler: ScheduledExecutorService,
) {
    /** The time ran out and the socket was closed; whatever the block made of that comes as the cause. */
    class Expired(cause: Throwable?) : IOException("No answer within the time limit", cause)

    fun <T> run(socket: Closeable, block: () -> T): T {
        // Settled once, by whichever comes first: the block returning or the time running out.
        val settled = AtomicBoolean(false)
        val closer = scheduler.schedule({
            if (settled.compareAndSet(false, true)) runCatching { socket.close() }
        }, timeoutMs, TimeUnit.MILLISECONDS)
        val outcome = try {
            Result.success(block())
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            closer.cancel(false)
        }
        // Lost the race: the socket is closed, even if the block got through just before.
        if (!settled.compareAndSet(false, true)) throw Expired(outcome.exceptionOrNull())
        return outcome.getOrThrow()
    }
}

/**
 * Lets Android finish leaving standby before asking ActivityManager to foreground Kodi.
 * Kore's WOL packet may wake the hardware while loopback ADB is still reconnecting, so a
 * failed ADB wake command must not prevent the later Kodi launch attempt. Kodi can also
 * ANR while Android finishes its SCREEN_OFF broadcast; verify after that timeout window
 * and relaunch once if Android returned to the launcher.
 */
internal class WakeAndLaunchKodiAction(
    private val wake: () -> Unit,
    private val launchKodi: () -> Unit,
    private val isKodiForeground: () -> Boolean,
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val onWakeFailure: (Exception) -> Unit = {},
) {
    companion object {
        internal const val WAKE_SETTLE_DELAY_MS = 2_500L
        internal const val KODI_VERIFY_DELAY_MS = 16_000L
    }

    fun run() {
        try {
            wake()
        } catch (e: Exception) {
            onWakeFailure(e)
        }
        sleep(WAKE_SETTLE_DELAY_MS)
        launchKodi()
        sleep(KODI_VERIFY_DELAY_MS)
        val kodiIsForeground = runCatching { isKodiForeground() }.getOrDefault(false)
        if (!kodiIsForeground) launchKodi()
    }
}
