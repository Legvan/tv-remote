package com.porter.tvremote

import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.BindException
import java.net.ServerSocket

class HttpServerPortTest {

    @Test
    fun defaultAvoidsKodiPort() {
        assertEquals(8081, HttpServerSettings.DEFAULT_PORT)
    }

    @Test
    fun configuredPortMustBeInTcpRange() {
        assertEquals(8082, HttpServerSettings.parsePort("8082"))
        assertEquals(1, HttpServerSettings.parsePort("1"))
        assertEquals(65535, HttpServerSettings.parsePort("65535"))
        assertNull(HttpServerSettings.parsePort("0"))
        assertNull(HttpServerSettings.parsePort("65536"))
        assertNull(HttpServerSettings.parsePort("not-a-port"))
    }

    @Test
    fun occupiedPortFailsBeforeKtorStarts() {
        ServerSocket(0).use { occupiedSocket ->
            assertThrows(BindException::class.java) {
                HttpServer.verifyPortAvailable(occupiedSocket.localPort)
            }
        }
    }
}
