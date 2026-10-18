package com.porter.tvremote

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * Embedded Ktor CIO HTTP server.
 *
 * Exposes the same REST API as the Python/Flask remote_server.py so the existing
 * index.html works without modification. The listening port is configurable; it
 * defaults to 8080 and can be moved if something else on the TV already uses it.
 *
 * Routes:
 *   GET  /                       → serve index.html from assets
 *   GET  /api/config             → { name, host, port }
 *   POST /api/key/{code}         → send keyevent via ADB loopback
 *   POST /api/launch/{name}      → launch app or "home" via ADB
 *   POST /api/assistant          → launch Google Assistant
 *   POST /api/text               → send text { "text": "..." } via ADB
 *   GET  /api/state              → { ok, screen, app }
 *   GET  /api/adb                → { connected } — ADB loopback health check
 */
class HttpServer(
    private val context: Context,
    private val adb: AdbController,
    val port: Int = HttpServerSettings.DEFAULT_PORT,
) {
    companion object {
        private const val TAG = "HttpServer"
        private const val HOST = "0.0.0.0"

        /**
         * Ktor CIO binds asynchronously, so detect an occupied port before starting it.
         * This makes the bind failure catchable by RemoteService instead of crashing the app.
         */
        internal fun verifyPortAvailable(port: Int) {
            ServerSocket().use { probe ->
                probe.bind(InetSocketAddress(HOST, port))
            }
        }
    }

    @Serializable
    data class ApiResult(val ok: Boolean, val error: String? = null)

    @Serializable
    data class StateResult(val ok: Boolean, val screen: String? = null, val app: String? = null, val error: String? = null)

    @Serializable
    data class ConfigResult(val name: String, val host: String, val port: Int)

    @Serializable
    data class AdbStatusResult(val connected: Boolean)

    @Serializable
    data class TextBody(val text: String = "")

    // RemoteService starts the server on a worker thread and stops it from another one, so a
    // Stop pressed right after Start can arrive mid-start. Without the lock and the flag the
    // stop would find no server yet, and the one still starting would keep the port.
    private val lifecycleLock = Any()
    private var server: ApplicationEngine? = null
    private var stopped = false

    fun start() {
        synchronized(lifecycleLock) {
            check(!stopped) { "HTTP server stopped before it started" }
            verifyPortAvailable(port)
            server = embeddedServer(CIO, port = port, host = HOST) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true })
                }
                routing {
                    setupRoutes()
                }
            }.also {
                it.start(wait = false)
                Log.i(TAG, "HTTP server started on port $port")
            }
        }
    }

    /** Blocks for up to the shutdown timeout (0.5 s); one-way, a stopped server cannot restart. */
    fun stop() {
        val engine = synchronized(lifecycleLock) {
            stopped = true
            server.also { server = null }
        }
        engine?.stop(250, 500)
        Log.i(TAG, "HTTP server stopped")
    }

    private fun Routing.setupRoutes() {
        // Capture Android Context here — inside Ktor route handlers `context` refers
        // to Ktor's ApplicationCall, which shadows the outer Android Context field.
        val androidContext = this@HttpServer.context

        // ── Static UI ──────────────────────────────────────────────────────────

        get("/") {
            val html = androidContext.assets.open("index.html").bufferedReader().readText()
            call.respondText(html, ContentType.Text.Html)
        }

        // ── Config ────────────────────────────────────────────────────────────

        get("/api/config") {
            val name = try {
                adb.shell("getprop ro.product.model").trim()
            } catch (_: Exception) { "TV Remote" }
            val host = getLanIp()
            call.respond(ConfigResult(name = name, host = host, port = AdbController.ADB_PORT))
        }

        // ── ADB health ────────────────────────────────────────────────────────

        get("/api/adb") {
            call.respond(AdbStatusResult(connected = adb.isConnected))
        }

        // ── Key event ─────────────────────────────────────────────────────────

        post("/api/key/{code}") {
            val code = call.parameters["code"]?.toIntOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, ApiResult(ok = false, error = "Invalid keycode"))
            adbCall(call) { adb.keyEvent(code) }
        }

        // ── App launch ────────────────────────────────────────────────────────

        post("/api/launch/{name}") {
            val name = call.parameters["name"] ?: ""
            adbCall(call) {
                when {
                    name == "home"            -> adb.goHome()
                    name in AdbController.APPS -> adb.launchApp(AdbController.APPS[name]!!)
                    name.isNotEmpty()          -> adb.shell("monkey -p $name -c android.intent.category.LAUNCHER 1")
                    else                       -> throw IllegalArgumentException("Unknown app: $name")
                }
            }
        }

        // ── Google Assistant ──────────────────────────────────────────────────

        post("/api/assistant") {
            adbCall(call) { adb.launchAssistant() }
        }

        // ── Text input ────────────────────────────────────────────────────────

        post("/api/text") {
            val body = runCatching { call.receive<TextBody>() }.getOrNull()
            val text = body?.text?.takeIf { it.isNotEmpty() }
                ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiResult(ok = false, error = "No text provided")
                )
            adbCall(call) { adb.sendText(text) }
        }

        // ── State ─────────────────────────────────────────────────────────────

        get("/api/state") {
            try {
                val screen = adb.screenState()
                val app    = adb.currentApp()
                call.respond(StateResult(ok = true, screen = screen, app = app))
            } catch (e: Exception) {
                Log.w(TAG, "State query failed: ${e.message}")
                call.respond(HttpStatusCode.InternalServerError,
                    StateResult(ok = false, error = e.message))
            }
        }
    }

    /** Execute an ADB action and respond with ApiResult JSON. */
    private suspend fun adbCall(call: ApplicationCall, action: () -> Unit) {
        try {
            action()
            call.respond(ApiResult(ok = true))
        } catch (e: Exception) {
            Log.w(TAG, "ADB call failed: ${e.message}")
            call.respond(HttpStatusCode.InternalServerError, ApiResult(ok = false, error = e.message))
        }
    }

    /** Returns the device's LAN IPv4 address, or "unknown" as fallback. */
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

/** The app's single SharedPreferences file. */
internal object Preferences {
    private const val NAME = "tv_remote_settings"

    fun of(context: Context): SharedPreferences =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
}

/** Persisted HTTP-port configuration shared by the activity and foreground service. */
internal object HttpServerSettings {
    /**
     * Unchanged from the versions already installed in the field: existing users keep
     * their bookmarks working after an update. Anyone whose port 8080 is taken — Kodi
     * being the usual culprit — can pick another one in the app.
     */
    const val DEFAULT_PORT = 8080
    private const val HTTP_PORT_KEY = "http_port"
    private val VALID_PORTS = 1..65535

    fun load(context: Context): Int {
        val stored = Preferences.of(context).getInt(HTTP_PORT_KEY, DEFAULT_PORT)
        return stored.takeIf(::isValidPort) ?: DEFAULT_PORT
    }

    fun save(context: Context, port: Int) {
        require(isValidPort(port)) { "Invalid HTTP port: $port" }
        Preferences.of(context)
            .edit()
            .putInt(HTTP_PORT_KEY, port)
            .apply()
    }

    internal fun parsePort(value: String): Int? = value.toIntOrNull()?.takeIf(::isValidPort)

    /**
     * Value for the −/+ buttons next to the port field, which exist so the port can be
     * changed with a TV remote alone. Steps from whatever the field holds and never leaves
     * the valid range; text that is not a number at all starts over from [DEFAULT_PORT].
     */
    internal fun stepPort(value: String, delta: Int): Int {
        val current = value.toIntOrNull()?.coerceIn(VALID_PORTS) ?: return DEFAULT_PORT
        return (current + delta).coerceIn(VALID_PORTS)
    }

    private fun isValidPort(port: Int): Boolean = port in VALID_PORTS
}
