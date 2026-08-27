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
    fun koreWakeOnLanPacketIsAcceptedAndDispatchesOnce() {
        var dispatchCount = 0
        val handler = YatsePacketHandler { dispatchCount++ }
        val packet = wakeOnLanPacket(byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44, 0x55))

        assertTrue(handler.handle(packet, packet.size))
        assertEquals(1, dispatchCount)
    }

    @Test
    fun malformedWakeOnLanPacketsAreIgnored() {
        var dispatchCount = 0
        val handler = YatsePacketHandler { dispatchCount++ }
        val validPacket = wakeOnLanPacket(byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44, 0x55))
        val invalidHeader = validPacket.copyOf().apply { this[0] = 0 }
        val mismatchedMac = validPacket.copyOf().apply { this[lastIndex] = 0 }

        assertFalse(handler.handle(invalidHeader, invalidHeader.size))
        assertFalse(handler.handle(mismatchedMac, mismatchedMac.size))
        assertFalse(handler.handle(validPacket, validPacket.size - 1))
        assertEquals(0, dispatchCount)
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

    private fun wakeOnLanPacket(macAddress: ByteArray): ByteArray =
        ByteArray(6 + 16 * macAddress.size).also { packet ->
            packet.fill(0xff.toByte(), 0, 6)
            for (offset in 6 until packet.size step macAddress.size) {
                macAddress.copyInto(packet, offset)
            }
        }
}
