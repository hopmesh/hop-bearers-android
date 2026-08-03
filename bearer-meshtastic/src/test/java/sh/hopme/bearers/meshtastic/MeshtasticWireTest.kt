package sh.hopme.bearers.meshtastic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Byte-level tests for the Meshtastic protobuf codec, the Hop link-frame grammar, fragmentation and
// reassembly. The fragment-count vectors MUST match bearers/meshtastic-vectors.json and the Apple suite;
// tools/meshtastic-parity.sh pins them on both platforms.
class MeshtasticWireTest {

    @Test fun varintRoundTrip() {
        for (v in listOf(0L, 1L, 127L, 128L, 255L, 256L, 300L, 16_383L, 16_384L, 1L shl 32)) {
            val w = ProtoWriter().varintField(1, v)
            val r = ProtoReader(w.toBytes())
            val (field, wire) = r.readTag()!!
            assertEquals(1, field); assertEquals(0, wire)
            assertEquals(v, r.readVarint())
        }
    }

    @Test fun fixed32LittleEndian() {
        val w = ProtoWriter().fixed32Field(6, 0x0102_0304L)
        assertArrayEquals(byteArrayOf(0x35, 0x04, 0x03, 0x02, 0x01), w.toBytes())
        val r = ProtoReader(w.toBytes()); r.readTag()
        assertEquals(0x0102_0304L, r.readFixed32())
    }

    @Test fun dataEncodeDecodeOnHopPort() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        assertArrayEquals(payload, MeshtasticProto.decodeHopData(MeshtasticProto.encodeData(payload)))
    }

    @Test fun dataOnWrongPortIgnored() {
        val w = ProtoWriter().varintField(1, 1L).bytesField(2, byteArrayOf(9, 9))
        assertNull(MeshtasticProto.decodeHopData(w.toBytes()))
    }

    @Test fun decodeFromRadioMyNodeNum() {
        val info = ProtoWriter().varintField(1, 4242L)
        val w = ProtoWriter().bytesField(3, info.toBytes())
        assertEquals(MeshInbound.MyNodeNum(4242L), MeshtasticProto.decodeFromRadio(w.toBytes()))
    }

    @Test fun decodeFromRadioHopPacket() {
        val pkt = ProtoWriter()
            .fixed32Field(1, 77L)
            .bytesField(4, MeshtasticProto.encodeData(byteArrayOf(0xAA.toByte(), 0xBB.toByte())))
        val w = ProtoWriter().bytesField(2, pkt.toBytes())
        assertEquals(
            MeshInbound.HopData(77L, byteArrayOf(0xAA.toByte(), 0xBB.toByte())),
            MeshtasticProto.decodeFromRadio(w.toBytes()),
        )
    }

    @Test fun decodeFromRadioSkipsUnknownFields() {
        val w = ProtoWriter().varintField(7, 1234L)   // config_complete_id
        assertNull(MeshtasticProto.decodeFromRadio(w.toBytes()))
    }

    @Test fun decodeToRadioPacketRoundTrip() {
        val frame = MeshtasticProto.encodeToRadioPacket(10L, 20L, 999L, 3, byteArrayOf(7, 8, 9))
        val r = ProtoReader(frame)
        val (field, wire) = r.readTag()!!
        assertEquals(1, field); assertEquals(2, wire)
        val pkt = r.readBytes()!!
        assertEquals(MeshInbound.HopData(10L, byteArrayOf(7, 8, 9)), MeshtasticProto.decodeMeshPacket(pkt))
    }

    @Test fun wantConfigEncodes() {
        val r = ProtoReader(MeshtasticProto.encodeWantConfig(0xABCDL))
        val (field, wire) = r.readTag()!!
        assertEquals(3, field); assertEquals(0, wire)
        assertEquals(0xABCDL, r.readVarint())
    }

    @Test fun truncatedFramesRefused() {
        assertNull(MeshtasticProto.decodeFromRadio(byteArrayOf(0x08)))               // varint, no body
        assertNull(MeshtasticProto.decodeFromRadio(byteArrayOf(0x12, 0x05, 0x00)))   // len 5, 1 present
    }

    @Test fun frameGrammar() {
        val id = ByteArray(16) { it.toByte() }
        val hello = MeshFrame.hello(id, true)
        assertEquals(M_HELLO, hello[0].toInt() and 0xff)
        assertArrayEquals(id, MeshFrame.helloPeerId(hello))
        assertEquals(1, hello[17].toInt())
        val ping = MeshFrame.ping(5L, 1000L)
        assertEquals(M_PING, ping[0].toInt() and 0xff)
        assertEquals(5L, MeshFrame.u64dec(ping.copyOfRange(1, ping.size), 0))
        assertArrayEquals(byteArrayOf(M_PONG.toByte(), 1, 2, 3), MeshFrame.pong(byteArrayOf(1, 2, 3)))
        assertArrayEquals(byteArrayOf(M_DATA.toByte(), 9, 9), MeshFrame.data(byteArrayOf(9, 9)))
        assertNull(MeshFrame.helloPeerId(byteArrayOf(M_HELLO.toByte(), 1, 2)))
    }

    @Test fun fragmentCounts() {
        val cases = listOf(0 to 1, 1 to 1, 200 to 1, 201 to 2, 400 to 2, 401 to 3, 1000 to 5)
        for ((len, frags) in cases) {
            val body = ByteArray(len) { 0x5A }
            val f = meshFragment(body, 1)!!
            assertEquals("len $len", frags, f.size)
            for (x in f) assertTrue(x.size <= MESH_FRAG_HEADER + MESH_MAX_CHUNK)
        }
    }

    @Test fun fragmentOversizeRefused() {
        assertNull(meshFragment(ByteArray(MESH_MAX_MESSAGE + 1), 1))
    }

    @Test fun reassembleSingleFragment() {
        val rz = MeshReassembler()
        val body = byteArrayOf(M_DATA.toByte(), 1, 2, 3)
        val frags = meshFragment(body, 7)!!
        assertEquals(1, frags.size)
        assertArrayEquals(body, rz.accept(5L, frags[0], 0L))
    }

    @Test fun reassembleMultiFragmentOutOfOrder() {
        val rz = MeshReassembler()
        val body = ByteArray(450) { (it and 0xff).toByte() }
        val frags = meshFragment(body, 3)!!
        assertEquals(3, frags.size)
        assertNull(rz.accept(2L, frags[2], 0L))
        assertNull(rz.accept(2L, frags[0], 0L))
        assertArrayEquals(body, rz.accept(2L, frags[1], 0L))
    }

    @Test fun peersDoNotCrossContaminate() {
        val rz = MeshReassembler()
        val body = ByteArray(250) { (it and 0xff).toByte() }
        val frags = meshFragment(body, 1)!!
        assertNull(rz.accept(10L, frags[0], 0L))
        assertNull(rz.accept(11L, frags[0], 0L))
        assertArrayEquals(body, rz.accept(10L, frags[1], 0L))
    }

    @Test fun staleEviction() {
        val rz = MeshReassembler()
        val frags = meshFragment(ByteArray(300), 1)!!
        assertNull(rz.accept(1L, frags[0], 0L))
        assertEquals(1, rz.partialPeerCount)
        rz.evictStale(MESH_REASSEMBLY_TTL_MS + 1)
        assertEquals(0, rz.partialPeerCount)
    }

    @Test fun forgetPeer() {
        val rz = MeshReassembler()
        assertNull(rz.accept(1L, meshFragment(ByteArray(300), 1)!![0], 0L))
        rz.forget(1L)
        assertEquals(0, rz.partialPeerCount)
    }

    @Test fun badFragmentHeaderRejected() {
        val rz = MeshReassembler()
        assertNull(rz.accept(1L, byteArrayOf(1, 2), 0L))
        assertNull(rz.accept(1L, byteArrayOf(0, 1, 3, 2), 0L))
        assertNull(rz.accept(1L, byteArrayOf(0, 1, 0, 0), 0L))
    }

    @Test fun dedupKeepRule() {
        assertTrue(meshKeepGreaterLeg(true))
        assertFalse(meshKeepGreaterLeg(false))
    }
}
