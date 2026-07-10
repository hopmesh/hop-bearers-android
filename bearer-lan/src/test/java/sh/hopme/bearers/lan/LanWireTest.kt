package sh.hopme.bearers.lan

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hop.nodeIdGreater
import sh.hop.toHex

/**
 * quality-net-03: LAN bearer wire codec + one-pipe-per-peer dedup keep-rule.
 *
 * These lock in the on-wire framing (byte-identical to :bearer-ble and apple/HopBearers, so
 * Android<->Apple LAN interop can't silently drift) and the greater-nodeId dedup rule (both ends must
 * agree on the SAME survivor, or a mutually-discovered pair keeps two pipes and double-delivers).
 */
class LanWireTest {

    private fun id(vararg bytes: Int) = ByteArray(bytes.size) { bytes[it].toByte() }
    private fun id16(fill: Int) = ByteArray(16) { fill.toByte() }

    // ---- length prefix round-trips exactly (big-endian) ----------------------

    @Test fun lengthPrefixRoundTrips() {
        for (n in intArrayOf(1, 2, 255, 256, 65_535, 65_536, 1_000_000, LAN_MAX_FRAME)) {
            val hdr = LanWire.lengthPrefix(n)
            assertEquals(4, hdr.size)
            assertEquals("len $n must decode back", n, LanWire.readLength(hdr))
        }
    }

    @Test fun lengthPrefixIsBigEndian() {
        // 0x01020304 -> bytes 01 02 03 04
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), LanWire.lengthPrefix(0x01020304))
    }

    @Test fun rejectsOutOfRangeLengths() {
        assertFalse("zero-length body is invalid", LanWire.isValidLength(0))
        assertFalse("over-cap body is invalid", LanWire.isValidLength(LAN_MAX_FRAME + 1))
        assertTrue(LanWire.isValidLength(1))
        assertTrue(LanWire.isValidLength(LAN_MAX_FRAME))
    }

    // ---- HELLO round-trips id + role ----------------------------------------

    @Test fun helloCarriesIdAndDialerRole() {
        val myId = id16(0xAB)
        val body = LanWire.hello(myId, isDialer = true)
        val frame = LanWire.decodeBody(body)!!
        assertEquals(L_HELLO, frame.type)
        assertArrayEquals(myId, LanWire.helloPeerId(body))
        assertTrue(LanWire.helloIsDialer(body))
    }

    @Test fun helloAcceptorRoleByte() {
        val body = LanWire.hello(id16(0x11), isDialer = false)
        assertFalse(LanWire.helloIsDialer(body))
        assertArrayEquals(id16(0x11), LanWire.helloPeerId(body))
    }

    @Test fun helloTooShortHasNoPeerId() {
        // A truncated HELLO (only 8 id bytes) must not yield a peerId (LanLink.handle guards on size>=17).
        val truncated = byteArrayOf(L_HELLO.toByte()) + ByteArray(8)
        assertNull(LanWire.helloPeerId(truncated))
    }

    // ---- PING seq/time round-trips ------------------------------------------

    @Test fun pingEncodesSeqAndTime() {
        val body = LanWire.ping(seq = 42L, nowMs = 1_700_000_000_000L)
        val frame = LanWire.decodeBody(body)!!
        assertEquals(L_PING, frame.type)
        // payload = [8B seq][8B nowMs]; decode back from the full body (offset 1 past the type tag).
        assertEquals(42L, LanWire.u64dec(body, 1))
        assertEquals(1_700_000_000_000L, LanWire.u64dec(body, 9))
    }

    // ---- DATA is the consumer seam: bytes survive a full encode/decode -------

    @Test fun dataFrameRoundTripsPayloadExactly() {
        val payload = "hello mesh, éà bytes".toByteArray() + byteArrayOf(0, 1, 2, 0x7f, -1)
        // Full on-wire: [len][type=DATA][payload]. Strip the length prefix as LanLink.readLoop does.
        val wire = LanWire.encodeFrame(LanWire.data(payload))
        val declaredLen = LanWire.readLength(wire.copyOfRange(0, 4))
        assertTrue(LanWire.isValidLength(declaredLen))
        val body = wire.copyOfRange(4, wire.size)
        assertEquals(declaredLen, body.size)
        val frame = LanWire.decodeBody(body)!!
        assertEquals(L_DATA, frame.type)
        assertArrayEquals("DATA payload must survive framing byte-for-byte", payload, frame.payload)
    }

    @Test fun emptyBodyDecodesToNull() {
        assertNull(LanWire.decodeBody(ByteArray(0)))
    }

    // ---- NSD instance-name <-> nodeId round-trip (peerIdFromName) ------------

    @Test fun instanceNameRoundTripsNodeId() {
        val nodeId = ByteArray(16) { (it * 7 + 3).toByte() }
        val name = nodeId.toHex()                        // the NSD instance name IS the 32-hex nodeId
        assertEquals(32, name.length)
        assertArrayEquals(nodeId, peerIdFromName(name))
    }

    @Test fun instanceNameRejectsMalformed() {
        assertNull(peerIdFromName(null))
        assertNull(peerIdFromName(""))
        assertNull(peerIdFromName("deadbeef"))                       // too short
        assertNull(peerIdFromName("z".repeat(32)))                   // non-hex
        assertNull(peerIdFromName("00".repeat(16) + "00"))           // too long
    }

    // ---- dedup keep-rule: both ends must agree on ONE survivor ---------------

    @Test fun bothEndsAgreeOnSameSurvivor() {
        // Two nodes, A (greater id) and B (lesser id), each discovers the other and dials, so each side
        // ends up with one dialed leg and one accepted leg. The keep-rule must pick the SAME physical
        // link on both ends: A keeps its DIALED leg, B keeps its ACCEPTED leg, and A's dialed leg IS
        // B's accepted leg (the A->B TCP connection). So exactly one pipe survives.
        val aGreater = LanDedup.decide(amGreater = true,  existingIsDialer = false, incomingIsDialer = true)
        val bGreater = LanDedup.decide(amGreater = false, existingIsDialer = false, incomingIsDialer = true)
        // A (greater) keeps its dialed leg; B (lesser) keeps its accepted leg. Order of existing/incoming
        // is arbitrary, so just assert A keeps a DIALED and B keeps an ACCEPTED leg.
        assertEquals("greater id keeps its dialed leg", DedupKeep.INCOMING, aGreater) // incoming is the dialer here
        assertEquals("lesser id keeps its accepted leg", DedupKeep.EXISTING, bGreater) // existing is the acceptor here
    }

    @Test fun greaterIdKeepsDialedRegardlessOfArrivalOrder() {
        // greater id => keepDialed=true, so whichever of the pair is the dialer wins, in either slot.
        assertEquals(DedupKeep.EXISTING, LanDedup.decide(true, existingIsDialer = true, incomingIsDialer = false))
        assertEquals(DedupKeep.INCOMING, LanDedup.decide(true, existingIsDialer = false, incomingIsDialer = true))
    }

    @Test fun lesserIdKeepsAcceptedRegardlessOfArrivalOrder() {
        // lesser id => keepDialed=false, so the ACCEPTOR (isDialer=false) wins, in either slot.
        assertEquals(DedupKeep.EXISTING, LanDedup.decide(false, existingIsDialer = false, incomingIsDialer = true))
        assertEquals(DedupKeep.INCOMING, LanDedup.decide(false, existingIsDialer = true, incomingIsDialer = false))
    }

    @Test fun tiebreakerMatchesTransportHelper() {
        // Sanity: the dedup input (amGreater) is exactly nodeIdGreater(myId, peerId) from the SDK.
        val big = ByteArray(16) { if (it == 0) 0xFF.toByte() else 0 }
        val small = ByteArray(16) { if (it == 0) 0x01.toByte() else 0 }
        assertTrue(nodeIdGreater(big, small))
        assertFalse(nodeIdGreater(small, big))
        // greater side keeps dialed, lesser side keeps accepted -> same physical A->B connection.
        assertEquals(DedupKeep.INCOMING, LanDedup.decide(nodeIdGreater(big, small), existingIsDialer = false, incomingIsDialer = true))
        assertEquals(DedupKeep.EXISTING, LanDedup.decide(nodeIdGreater(small, big), existingIsDialer = false, incomingIsDialer = true))
    }
}
