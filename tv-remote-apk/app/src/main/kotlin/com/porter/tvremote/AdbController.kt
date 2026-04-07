package com.porter.tvremote

import android.content.Context
import android.util.Base64
import android.util.Log
import com.cgutman.adblib.AdbBase64
import com.cgutman.adblib.AdbConnection
import com.cgutman.adblib.AdbCrypto
import java.io.File
import java.net.Socket

/**
 * Manages the loopback ADB connection to the TV's own ADB daemon at 127.0.0.1:5555.
 *
 * Architecture: instead of INJECT_EVENTS (requires system signature), we connect back
 * to the device's ADB daemon via the ADB wire protocol (cgutman/AdbLib).
 * The daemon grants us `shell` privilege — sufficient for input keyevent, am start, dumpsys.
 *
 * First run: the TV will show "Allow USB Debugging?" — the user must approve it once.
 * The RSA key pair is saved in app-private storage and reused on subsequent runs.
 */
class AdbController(private val context: Context) {

    companion object {
        private const val TAG = "AdbController"
        const val ADB_HOST = "127.0.0.1"
        const val ADB_PORT = 5555

        /** Maps URL-friendly names to Android activity strings (mirrors Python APPS dict). */
        val APPS = mapOf(
            "youtube"  to "com.google.android.youtube.tv/com.google.android.apps.youtube.tv.activity.ShellActivity",
            "netflix"  to "com.netflix.ninja/.MainActivity",
            "prime"    to "com.amazon.amazonvideo.livingroom/.MainActivity",
            "disney"   to "com.disney.disneyplus/.MainActivity",
            "settings" to "com.android.settings/.Settings",
            "spotify"  to "com.spotify.tv.android/.SpotifyTVActivity",
            "kodi"     to "org.xbmc.kodi/.Splash",
        )

        /** AdbLib Base64 adapter using Android's built-in Base64 codec. */
        val base64: AdbBase64 = AdbBase64 { data ->
            Base64.encodeToString(data, Base64.NO_WRAP)
        }
    }

    @Volatile private var connection: AdbConnection? = null
    private val lock = Any()

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

    /** Connect to the loopback ADB daemon. Returns true on success. */
    fun connect(): Boolean {
        synchronized(lock) {
            return try {
                val socket = Socket(ADB_HOST, ADB_PORT)
                val conn   = AdbConnection.create(socket, crypto)
                // connect() blocks until authenticated (triggers "Allow USB Debugging?" on first run)
                conn.connect()
                connection = conn
                Log.i(TAG, "ADB loopback connected ($ADB_HOST:$ADB_PORT)")
                true
            } catch (e: Exception) {
                Log.e(TAG, "ADB connect failed: ${e.message}")
                connection = null
                false
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
                val conn = connection ?: if (attempt == 0 && connect()) connection!! else
                    throw IllegalStateException(
                        "ADB not connected — enable ADB over network in Developer Options"
                    )
                return try {
                    execShell(conn, cmd)
                } catch (e: Exception) {
                    Log.w(TAG, "Shell error (attempt $attempt): ${e.message}")
                    connection = null
                    if (attempt == 1) throw e else continue
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

    fun disconnect() {
        synchronized(lock) {
            runCatching { connection?.close() }
            connection = null
        }
    }

    val isConnected: Boolean get() = connection != null
}
