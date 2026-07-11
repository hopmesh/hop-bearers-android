package sh.hopme.bearers.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * quality-cov: the iBeacon (Layer C) relaunch-signal builder + the BLE frame-grammar constants.
 *
 * [iBeaconPayload] is the exact 23-byte Apple-manufacturer-data blob the peripheral advertises to
 * RELAUNCH a force-quit iOS peer (byte-matched to iOS BeaconWake.swift). A single wrong byte here means
 * a killed iPhone never wakes, so pin the layout precisely: subtype/length prefix, big-endian UUID, BE
 * major/minor, and the signed measured-power byte. Pure JVM (ByteBuffer + UUID), no radios.
 */
class BleBeaconTest {

    @Test fun iBeaconPayloadHasTheExact23ByteAppleLayout() {
        // A known UUID with distinct high/low halves so a byte-swap would show.
        val uuid = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff")
        val payload = iBeaconPayload(uuid, major = 0x1234, minor = 0x5678, measuredPowerDbm = -59)

        assertEquals("iBeacon manufacturer-data is exactly 23 bytes", 23, payload.size)
        // [0..1] iBeacon subtype (0x02) + length (0x15 = 21).
        assertEquals(0x02.toByte(), payload[0])
        assertEquals(0x15.toByte(), payload[1])
        // [2..17] the 128-bit UUID in network (big-endian) order — high 8 bytes then low 8 bytes.
        val expectedUuid = byteArrayOf(
            0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77,
            0x88.toByte(), 0x99.toByte(), 0xaa.toByte(), 0xbb.toByte(),
            0xcc.toByte(), 0xdd.toByte(), 0xee.toByte(), 0xff.toByte(),
        )
        assertArrayEquals(expectedUuid, payload.copyOfRange(2, 18))
        // [18..19] major BE, [20..21] minor BE.
        assertArrayEquals(byteArrayOf(0x12, 0x34), payload.copyOfRange(18, 20))
        assertArrayEquals(byteArrayOf(0x56, 0x78), payload.copyOfRange(20, 22))
        // [22] measured power as a signed byte: -59 -> 0xC5.
        assertEquals(0xC5.toByte(), payload[22])
    }

    @Test fun iBeaconMajorMinorAreTruncatedToUnsigned16Bits() {
        // Major/minor are 16-bit fields; only the low two bytes survive (matches iOS + the wire spec).
        val payload = iBeaconPayload(BEACON_UUID, major = 0x1_ABCD, minor = 0xFFFF, measuredPowerDbm = 0)
        assertArrayEquals(byteArrayOf(0xAB.toByte(), 0xCD.toByte()), payload.copyOfRange(18, 20))
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xFF.toByte()), payload.copyOfRange(20, 22))
        assertEquals(0x00.toByte(), payload[22])
    }

    @Test fun beaconUuidByteMatchesTheIosRelaunchUuid() {
        // F-40: the app + both platforms MUST monitor/emit the SAME beacon UUID or a killed iPhone never
        // wakes. Pin the literal so a rename can't silently split Android's emit from iOS's monitor.
        assertEquals("7ED7BEAC-3C2A-4F19-9B8E-1A2B3C4D5E6F", BEACON_UUID.toString().uppercase())
        // Its bytes round-trip through the iBeacon builder unchanged.
        val payload = iBeaconPayload(BEACON_UUID, 1, 2, -59)
        val hi = BEACON_UUID.mostSignificantBits
        assertEquals((hi ushr 56).toByte(), payload[2])   // 0x7E
    }

    @Test fun frameTypeTagsMatchTheCrossPlatformGrammar() {
        // The transport handshake/keepalive/data tags are byte-identical to :bearer-lan (LanWire) and to
        // apple/HopBearers, so Android<->Apple BLE can't silently drift. HELLO/PING/PONG stay internal;
        // DATA (0x10) is the only tag that surfaces to the consumer.
        assertEquals(0x01, FRAME_HELLO)
        assertEquals(0x02, FRAME_PING)
        assertEquals(0x03, FRAME_PONG)
        assertEquals(0x10, FRAME_DATA)
        // All distinct (a collision would misroute a keepalive as consumer data).
        assertEquals(4, setOf(FRAME_HELLO, FRAME_PING, FRAME_PONG, FRAME_DATA).size)
    }

    @Test fun appleCompanyIdAndMfgIdAreTheAdvertisingConstants() {
        assertEquals("iBeacon rides Apple's company id", 0x004C, APPLE_COMPANY_ID)
        assertTrue("the Hop manufacturer id is the 16-bit test/local range 0xFFFF", MFG_ID == 0xFFFF)
    }
}
