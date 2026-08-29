package com.porter.tvremote

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.net.StandardProtocolFamily
import java.nio.channels.DatagramChannel
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Receives the single-purpose UDP commands sent by Yatse Remote Starter and Kore.
 *
 * Packet contents are used only to recognize [YATSE_START_MARKER] or a standard
 * Wake-on-LAN magic packet. They are never passed to ADB or interpreted as commands.
 */
class YatseStarter(
    private val scope: CoroutineScope,
    val port: Int = DEFAULT_PORT,
    private val socketFactory: (Int) -> DatagramSocket = ::createIpv4Socket,
    private val macAddressReader: () -> Set<String> = ::readLocalMacAddresses,
    private val onTrigger: () -> Unit,
) {
    companion object {
        private const val TAG = "YatseStarter"
        const val DEFAULT_PORT = 5600
        private const val MAX_PACKET_SIZE = 512
        internal const val MAC_LENGTH = 6

        /** Bind IPv4 explicitly because some Android TV kernels make wildcard UDP IPv6-only. */
        internal fun createIpv4Socket(port: Int): DatagramSocket =
            DatagramChannel.open(StandardProtocolFamily.INET).socket().apply {
                bind(ipv4BindAddress(port))
            }

        internal fun ipv4BindAddress(port: Int) = InetSocketAddress(
            InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0)),
            port,
        )

        /**
         * This device's own interface MACs, lower-case hex without separators.
         *
         * Android 11+ hides hardware addresses from ordinary apps, so this is usually
         * empty in practice and [YatsePacketHandler] then accepts any well-formed magic
         * packet — the behaviour this listener has always had. Where the platform does
         * answer, a Wake-on-LAN packet aimed at some other machine on the LAN no longer
         * launches Kodi on the TV.
         */
        internal fun readLocalMacAddresses(): Set<String> = try {
            NetworkInterface.getNetworkInterfaces()
                .asSequence()
                .mapNotNull { runCatching { it.hardwareAddress }.getOrNull() }
                .filter { it.size == MAC_LENGTH }
                .map(::macToHex)
                .toSet()
        } catch (e: Exception) {
            Log.w(TAG, "Could not read local MAC addresses", e)
            emptySet()
        }

        internal fun macToHex(mac: ByteArray): String =
            mac.joinToString("") { byte -> "%02x".format(byte) }
    }

    /** Read once: interface addresses do not change while the service is up. */
    private val localMacAddresses: Set<String> by lazy { macAddressReader() }

    private val actionInFlight = AtomicBoolean(false)
    private val packetHandler = YatsePacketHandler({ localMacAddresses }) { dispatchTrigger() }

    private val lifecycleLock = Any()
    private var receiveJob: Job? = null
    private var socket: DatagramSocket? = null

    fun start() {
        synchronized(lifecycleLock) {
            if (receiveJob?.isActive == true) return
            receiveJob = scope.launch(Dispatchers.IO) { receiveLoop() }
        }
    }

    fun stop() {
        val jobToCancel: Job?
        val socketToClose: DatagramSocket?
        synchronized(lifecycleLock) {
            jobToCancel = receiveJob
            socketToClose = socket
            receiveJob = null
            socket = null
        }
        jobToCancel?.cancel()
        socketToClose?.close()
    }

    /**
     * Runs the wake action off the receive loop.
     *
     * The action deliberately takes the better part of twenty seconds — it waits for the
     * TV to finish leaving standby before checking on Kodi — and remote apps send their
     * wake packet more than once. Running it inline would stop the socket draining and
     * queue one whole action per duplicate, so at most one runs and the rest are dropped.
     */
    private fun dispatchTrigger() {
        if (!actionInFlight.compareAndSet(false, true)) {
            Log.i(TAG, "Wake action already running — ignoring duplicate packet")
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                onTrigger()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "UDP wake/Kodi action failed", e)
            } finally {
                actionInFlight.set(false)
            }
        }
    }

    private suspend fun receiveLoop() {
        val currentJob = currentCoroutineContext()[Job]
        var openedSocket: DatagramSocket? = null
        try {
            val listenerSocket = socketFactory(port)
            openedSocket = listenerSocket
            synchronized(lifecycleLock) {
                if (receiveJob !== currentJob || currentJob?.isActive != true) {
                    listenerSocket.close()
                    return
                }
                socket = listenerSocket
            }
            Log.i(TAG, "Yatse UDP starter listening on port $port")

            val buffer = ByteArray(MAX_PACKET_SIZE)
            while (currentCoroutineContext().isActive) {
                val packet = DatagramPacket(buffer, buffer.size)
                listenerSocket.receive(packet)
                try {
                    if (packetHandler.handle(packet.data, packet.length)) {
                        Log.i(TAG, "UDP start request received from ${packet.address?.hostAddress}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not handle UDP packet", e)
                }
            }
        } catch (e: SocketException) {
            if (currentCoroutineContext().isActive) {
                Log.e(TAG, "Yatse UDP listener failed", e)
            } else {
                Log.i(TAG, "Yatse UDP listener stopped")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Yatse UDP listener failed", e)
        } finally {
            synchronized(lifecycleLock) {
                if (socket === openedSocket) socket = null
                if (receiveJob === currentJob) receiveJob = null
            }
            openedSocket?.close()
        }
    }
}

internal const val YATSE_START_MARKER = "YatseStart-Xbmc"

/** Pure packet parsing/dispatch kept separate from Android socket lifecycle for unit tests. */
internal class YatsePacketHandler(
    private val localMacAddresses: () -> Set<String> = { emptySet() },
    private val onTrigger: () -> Unit,
) {
    fun handle(data: ByteArray, length: Int): Boolean {
        if (!isStartPacket(data, length)) return false
        onTrigger()
        return true
    }

    private fun isStartPacket(data: ByteArray, length: Int): Boolean {
        if (length <= 0 || length > data.size) return false
        wakeOnLanTargetMac(data, length)?.let { return addressesThisDevice(it) }
        return String(data, 0, length, Charsets.UTF_8).contains(YATSE_START_MARKER)
    }

    /**
     * A magic packet names the machine it is meant for. Ignore ones aimed elsewhere when
     * the platform lets us read our own addresses, and accept them when it does not.
     */
    private fun addressesThisDevice(targetMac: String): Boolean {
        val known = localMacAddresses()
        return known.isEmpty() || targetMac in known
    }

    companion object {
        /**
         * Kore sends the standard 6 x FF + 16 x target-MAC Wake-on-LAN payload.
         * Returns the target MAC as lower-case hex, or null when this is not one.
         */
        internal fun wakeOnLanTargetMac(data: ByteArray, length: Int): String? {
            val macLength = YatseStarter.MAC_LENGTH
            val headerLength = 6
            val repetitions = 16
            if (length != headerLength + repetitions * macLength) return null

            for (index in 0 until headerLength) {
                if (data[index] != 0xff.toByte()) return null
            }

            for (repetition in 1 until repetitions) {
                val offset = headerLength + repetition * macLength
                for (macIndex in 0 until macLength) {
                    if (data[offset + macIndex] != data[headerLength + macIndex]) return null
                }
            }
            return YatseStarter.macToHex(data.copyOfRange(headerLength, headerLength + macLength))
        }
    }
}
