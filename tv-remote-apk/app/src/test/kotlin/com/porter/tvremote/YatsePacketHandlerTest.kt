package com.porter.tvremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address

class YatsePacketHandlerTest {

    @Test
    fun listenerUsesIpv4BindAddress() {
        assertTrue(YatseStarter.ipv4BindAddress(0).address is Inet4Address)
    }

    @Test
    fun listenerCreatesIpv4DatagramChannel() {
        YatseStarter.createIpv4Socket(0).use { socket ->
            assertTrue(socket.localAddress is Inet4Address)
            assertTrue(socket.channel?.isOpen == true)
        }
    }

    @Test
    fun exactMarkerIsAcceptedAndDispatchesOnce() {
        var dispatchCount = 0
        val handler = YatsePacketHandler { dispatchCount++ }
        val packet = YATSE_START_MARKER.toByteArray()

        assertTrue(handler.handle(packet, packet.size))
        assertEquals(1, dispatchCount)
    }

    @Test
    fun markerWithProtocolFramingIsAccepted() {
        var dispatchCount = 0
        val handler = YatsePacketHandler { dispatchCount++ }
        val packet = "prefix:$YATSE_START_MARKER\n".toByteArray()

        assertTrue(handler.handle(packet, packet.size))
        assertEquals(1, dispatchCount)
    }

    @Test
    fun unrelatedPacketIsIgnored() {
        var dispatchCount = 0
        val handler = YatsePacketHandler { dispatchCount++ }
        val packet = "wake kodi".toByteArray()

        assertFalse(handler.handle(packet, packet.size))
        assertEquals(0, dispatchCount)
    }

    @Test
    fun bytesOutsideDatagramLengthAreIgnored() {
        var dispatchCount = 0
        val handler = YatsePacketHandler { dispatchCount++ }
        val packet = "ignored$YATSE_START_MARKER".toByteArray()

        assertFalse(handler.handle(packet, "ignored".length))
        assertEquals(0, dispatchCount)
    }

    @Test
    fun invalidPacketLengthsAreIgnored() {
        var dispatchCount = 0
        val handler = YatsePacketHandler { dispatchCount++ }
        val packet = YATSE_START_MARKER.toByteArray()

        assertFalse(handler.handle(packet, 0))
        assertFalse(handler.handle(packet, packet.size + 1))
        assertEquals(0, dispatchCount)
    }
}
