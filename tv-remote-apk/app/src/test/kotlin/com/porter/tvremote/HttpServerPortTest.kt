package com.porter.tvremote

import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.BindException
import java.net.ServerSocket

class HttpServerPortTest {

    @Test
    fun occupiedPortFailsBeforeKtorStarts() {
        ServerSocket(0).use { occupiedSocket ->
            assertThrows(BindException::class.java) {
                HttpServer.verifyPortAvailable(occupiedSocket.localPort)
            }
        }
    }
}
