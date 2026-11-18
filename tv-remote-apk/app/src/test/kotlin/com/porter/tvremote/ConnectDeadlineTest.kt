package com.porter.tvremote

import com.cgutman.adblib.AdbConnection
import com.cgutman.adblib.AdbCrypto
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ConnectDeadlineTest {

    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    @After
    fun shutDownScheduler() {
        scheduler.shutdownNow()
    }

    @Test
    fun `returns the result and leaves the socket open when the block finishes in time`() {
        val socket = RecordingCloseable()

        val result = ConnectDeadline(100, scheduler).run(socket) { 42 }
        Thread.sleep(300) // well past the deadline, which must have been called off

        assertEquals(42, result)
        assertFalse(socket.closed)
    }

    @Test
    fun `passes on a failure the deadline did not cause`() {
        val refused = IOException("Connection refused")

        val thrown = assertThrows(IOException::class.java) {
            ConnectDeadline(10_000, scheduler).run(RecordingCloseable()) { throw refused }
        }

        assertSame(refused, thrown)
    }

    @Test
    fun `reports Expired when the block gets through only after the socket was closed`() {
        val socket = RecordingCloseable()

        assertThrows(ConnectDeadline.Expired::class.java) {
            ConnectDeadline(50, scheduler).run(socket) {
                assertTrue(socket.awaitClose())
                "too late"
            }
        }
    }

    @Test
    fun `AdbLib connect gives up when the prompt is never accepted`() {
        // Takes the connection and never answers, as adbd does once the prompt is closed with Back.
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { silentAdbd ->
            val socket = Socket(silentAdbd.inetAddress, silentAdbd.localPort)
            val connection = AdbConnection.create(socket, crypto())
            val startedAt = System.nanoTime()

            val thrown = assertThrows(ConnectDeadline.Expired::class.java) {
                ConnectDeadline(300, scheduler).run(socket) { connection.connect() }
            }

            assertTrue(socket.isClosed)
            assertTrue(thrown.cause is IOException)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) < 10_000)
        }
    }

    private fun crypto(): AdbCrypto =
        AdbCrypto.generateAdbKeyPair { data -> Base64.getEncoder().encodeToString(data) }

    private class RecordingCloseable : Closeable {
        private val closeCalled = CountDownLatch(1)

        val closed: Boolean get() = closeCalled.count == 0L

        override fun close() = closeCalled.countDown()

        fun awaitClose(): Boolean = closeCalled.await(10, TimeUnit.SECONDS)
    }
}
