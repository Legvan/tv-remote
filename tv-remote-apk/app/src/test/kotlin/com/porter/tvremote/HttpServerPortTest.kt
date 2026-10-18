package com.porter.tvremote

import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.BindException
import java.net.ServerSocket

class HttpServerPortTest {

    @Test
    fun defaultKeepsThePortAlreadyInTheField() {
        assertEquals(8080, HttpServerSettings.DEFAULT_PORT)
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
    fun stepperMovesThePortByOne() {
        assertEquals(8081, HttpServerSettings.stepPort("8080", +1))
        assertEquals(8079, HttpServerSettings.stepPort("8080", -1))
    }

    @Test
    fun stepperStaysInTcpRange() {
        assertEquals(1, HttpServerSettings.stepPort("1", -1))
        assertEquals(65535, HttpServerSettings.stepPort("65535", +1))
        // Out-of-range text typed with a keyboard is pulled back into range first.
        assertEquals(1, HttpServerSettings.stepPort("0", -1))
        assertEquals(2, HttpServerSettings.stepPort("0", +1))
        assertEquals(65534, HttpServerSettings.stepPort("99999", -1))
        assertEquals(65535, HttpServerSettings.stepPort("99999", +1))
    }

    @Test
    fun stepperRestartsFromDefaultWhenFieldIsNotANumber() {
        assertEquals(HttpServerSettings.DEFAULT_PORT, HttpServerSettings.stepPort("", +1))
        assertEquals(HttpServerSettings.DEFAULT_PORT, HttpServerSettings.stepPort("", -1))
    }

    @Test
    fun steppedPortIsAlwaysAcceptedOnStart() {
        listOf("", "0", "1", "8080", "65535", "99999").forEach { field ->
            listOf(-1, +1).forEach { delta ->
                val stepped = HttpServerSettings.stepPort(field, delta)
                assertEquals(stepped, HttpServerSettings.parsePort(stepped.toString()))
            }
        }
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
