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
import java.net.SocketException
import java.net.StandardProtocolFamily
import java.nio.channels.DatagramChannel

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
    onTrigger: () -> Unit,
) {
    companion object {
        private const val TAG = "YatseStarter"
        const val DEFAULT_PORT = 5600
        private const val MAX_PACKET_SIZE = 512

        /** Bind IPv4 explicitly because some Android TV kernels make wildcard UDP IPv6-only. */
        internal fun createIpv4Socket(port: Int): DatagramSocket =
            DatagramChannel.open(StandardProtocolFamily.INET).socket().apply {
                bind(ipv4BindAddress(port))
            }

        internal fun ipv4BindAddress(port: Int) = InetSocketAddress(
            InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0)),
            port,
        )
    }

    private val packetHandler = YatsePacketHandler(onTrigger)
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
                        Log.i(TAG, "UDP start request received from ${packet.address.hostAddress}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "UDP wake/Kodi action failed", e)
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
internal class YatsePacketHandler(private val onTrigger: () -> Unit) {
    fun handle(data: ByteArray, length: Int): Boolean {
        if (!isStartPacket(data, length)) return false
        onTrigger()
        return true
    }

    companion object {
        fun isStartPacket(data: ByteArray, length: Int): Boolean {
            if (length <= 0 || length > data.size) return false
            return isWakeOnLanPacket(data, length) ||
                String(data, 0, length, Charsets.UTF_8).contains(YATSE_START_MARKER)
        }

        /** Kore sends the standard 6 x FF + 16 x target-MAC Wake-on-LAN payload. */
        private fun isWakeOnLanPacket(data: ByteArray, length: Int): Boolean {
            val macLength = 6
            val headerLength = 6
            val repetitions = 16
            if (length != headerLength + repetitions * macLength) return false

            for (index in 0 until headerLength) {
                if (data[index] != 0xff.toByte()) return false
            }

            for (repetition in 1 until repetitions) {
                val offset = headerLength + repetition * macLength
                for (macIndex in 0 until macLength) {
                    if (data[offset + macIndex] != data[headerLength + macIndex]) return false
                }
            }
            return true
        }
    }
}
