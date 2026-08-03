package sh.hopme.bearers.meshtastic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import sh.hop.HopRole
import sh.hop.LinkSink

// Drives the full MeshtasticBearer state machine against a fake radio: connect handshake, discovery via
// HELLO, reassembly of inbound DATA, outbound fragmentation, keepalive PING/PONG, and the dead-link reap.
// No Meshtastic hardware and no real BLE: the radio seam is faked, so only the state machine runs.
//
// Runs under Robolectric so android.util.Log is shadowed (MeshtasticBearer logs on every lifecycle edge:
// start, radio connect, hello-recv, link-down). The pure wire codec has no Android dependency at all and
// is covered by MeshtasticWireTest as a plain JVM test.
@RunWith(RobolectricTestRunner::class)
class MeshtasticBearerTest {
    private val myId = byteArrayOf(0x80.toByte()) + ByteArray(15)
    private val peerId = byteArrayOf(0x01) + ByteArray(15)
    private val peerNode = 4242L

    private class FakeRadio : MeshtasticRadio {
        override var onConnect: (() -> Unit)? = null
        override var onDisconnect: (() -> Unit)? = null
        override var onFromRadio: ((ByteArray) -> Unit)? = null
        val sent = ArrayList<ByteArray>()
        var started = false
        var stopped = false
        override fun start() { started = true }
        override fun stop() { stopped = true }
        override fun sendToRadio(bytes: ByteArray) { sent.add(bytes) }

        fun fireConnect() = onConnect?.invoke()
        fun fireDisconnect() = onDisconnect?.invoke()
        fun deliverMyNodeNum(num: Long) {
            val info = ProtoWriter().varintField(1, num)
            onFromRadio?.invoke(ProtoWriter().bytesField(3, info.toBytes()).toBytes())
        }
        fun deliverHopPacket(from: Long, payload: ByteArray) {
            val pkt = ProtoWriter().fixed32Field(1, from).bytesField(4, MeshtasticProto.encodeData(payload))
            onFromRadio?.invoke(ProtoWriter().bytesField(2, pkt.toBytes()).toBytes())
        }
        fun clearSent() = sent.clear()
    }

    private class RecordingSink : LinkSink {
        data class Up(val link: Long, val dialer: Boolean, val peer: ByteArray)
        val ups = ArrayList<Up>()
        val bytesEvents = ArrayList<Pair<Long, ByteArray>>()
        val downs = ArrayList<Long>()
        override fun linkUp(link: Long, role: HopRole, peerId: ByteArray) {
            ups.add(Up(link, role == HopRole.DIALER, peerId))
        }
        override fun linkBytes(link: Long, bytes: ByteArray) { bytesEvents.add(link to bytes) }
        override fun linkDown(link: Long) { downs.add(link) }
    }

    // ---- decode helpers: reconstruct the frames the bearer transmitted --------------------------------

    private fun toRadioFragment(bytes: ByteArray): Pair<Long, ByteArray>? {
        val r = ProtoReader(bytes)
        val (field, wire) = r.readTag() ?: return null
        if (field != 1 || wire != 2) return null
        val pkt = r.readBytes() ?: return null
        val pr = ProtoReader(pkt)
        var to = 0L
        var data: ByteArray? = null
        while (true) {
            val (f, w) = pr.readTag() ?: break
            when {
                f == 2 && w == 5 -> to = pr.readFixed32() ?: 0L
                f == 4 && w == 2 -> data = pr.readBytes()
                else -> if (!pr.skip(w)) break
            }
        }
        val d = data ?: return null
        val payload = MeshtasticProto.decodeHopData(d) ?: return null
        return to to payload
    }

    private fun allFrames(sends: List<ByteArray>): List<Pair<Long, ByteArray>> {
        data class P(val count: Int, val chunks: HashMap<Int, ByteArray>, val to: Long)
        val partial = HashMap<String, P>()
        val out = ArrayList<Pair<Long, ByteArray>>()
        for (s in sends) {
            val (to, frag) = toRadioFragment(s) ?: continue
            val h = MeshFragHeader.parse(frag) ?: continue
            val key = "$to-${h.msgId}"
            val p = partial.getOrPut(key) { P(h.count, HashMap(), to) }
            p.chunks[h.index] = h.chunk
            if (p.chunks.size == p.count) {
                val body = ArrayList<Byte>()
                for (i in 0 until p.count) p.chunks[i]?.forEach { body.add(it) }
                out.add(to to body.toByteArray())
                partial.remove(key)
            }
        }
        return out
    }

    private fun frameTo(dest: Long, sends: List<ByteArray>): ByteArray? =
        allFrames(sends).firstOrNull { it.first == dest }?.second

    // ---- fixtures -------------------------------------------------------------------------------------

    private fun makeBearer(): Triple<MeshtasticBearer, FakeRadio, RecordingSink> {
        val radio = FakeRadio()
        val bearer = MeshtasticBearer(myId, radio)
        val sink = RecordingSink()
        bearer.sink = sink
        bearer.start()
        bearer.awaitIdle()
        return Triple(bearer, radio, sink)
    }

    private fun connect(bearer: MeshtasticBearer, radio: FakeRadio) {
        radio.fireConnect()
        radio.deliverMyNodeNum(7L)
        bearer.awaitIdle()
    }

    private fun deliverFrame(bearer: MeshtasticBearer, radio: FakeRadio, frame: ByteArray) {
        for (frag in meshFragment(frame, 55)!!) radio.deliverHopPacket(peerNode, frag)
        bearer.awaitIdle()
    }

    private fun bringLinkUp(bearer: MeshtasticBearer, radio: FakeRadio) {
        connect(bearer, radio)
        radio.clearSent()
        deliverFrame(bearer, radio, MeshFrame.hello(peerId, false))
    }

    // ---- tests ----------------------------------------------------------------------------------------

    @Test fun startStartsRadio() {
        val (_, radio, _) = makeBearer()
        assertTrue(radio.started)
    }

    @Test fun connectRequestsConfigAndBeaconsHello() {
        val (bearer, radio, _) = makeBearer()
        radio.fireConnect()
        bearer.awaitIdle()
        assertTrue(radio.sent.any { toRadioFragment(it) == null && it.isNotEmpty() })  // want_config
        val hello = frameTo(MESH_BROADCAST_ADDR, radio.sent)
        assertEquals(M_HELLO, hello!![0].toInt() and 0xff)
        assertArrayEquals(myId, MeshFrame.helloPeerId(hello))
    }

    @Test fun learnsMyNodeNum() {
        val (bearer, radio, _) = makeBearer()
        radio.deliverMyNodeNum(99L)
        assertEquals(99L, bearer.myNodeNumForTest())
    }

    @Test fun inboundHelloSurfacesLinkUpAsDialer() {
        val (bearer, radio, sink) = makeBearer()
        bringLinkUp(bearer, radio)
        assertEquals(1, sink.ups.size)
        assertArrayEquals(peerId, sink.ups[0].peer)
        assertTrue(sink.ups[0].dialer)   // we are the greater id -> Noise initiator
        assertEquals(1, bearer.linkCountForTest())
        val reply = frameTo(peerNode, radio.sent)
        assertEquals(M_HELLO, reply!![0].toInt() and 0xff)
        assertArrayEquals(myId, MeshFrame.helloPeerId(reply))
        assertEquals(1, reply[17].toInt())
    }

    @Test fun duplicateHelloDoesNotDoubleSurface() {
        val (bearer, radio, sink) = makeBearer()
        bringLinkUp(bearer, radio)
        deliverFrame(bearer, radio, MeshFrame.hello(peerId, false))
        assertEquals(1, sink.ups.size)
        assertEquals(1, bearer.linkCountForTest())
    }

    @Test fun inboundDataSurfacesBytes() {
        val (bearer, radio, sink) = makeBearer()
        bringLinkUp(bearer, radio)
        val payload = ByteArray(350) { (it and 0xff).toByte() }
        deliverFrame(bearer, radio, MeshFrame.data(payload))
        assertEquals(1, sink.bytesEvents.size)
        assertArrayEquals(payload, sink.bytesEvents[0].second)
    }

    @Test fun dataBeforeLinkUpDropped() {
        val (bearer, radio, sink) = makeBearer()
        connect(bearer, radio)
        deliverFrame(bearer, radio, MeshFrame.data(byteArrayOf(1, 2, 3)))
        assertTrue(sink.bytesEvents.isEmpty())
    }

    @Test fun sendFragmentsToPeer() {
        val (bearer, radio, sink) = makeBearer()
        bringLinkUp(bearer, radio)
        val link = sink.ups[0].link
        radio.clearSent()
        val payload = ByteArray(500) { (it and 0xff).toByte() }
        bearer.send(payload, link)
        bearer.awaitIdle()
        assertTrue(allFrames(radio.sent).all { it.first == peerNode })
        val body = frameTo(peerNode, radio.sent)!!
        assertEquals(M_DATA, body[0].toInt() and 0xff)
        assertArrayEquals(payload, body.copyOfRange(1, body.size))
    }

    @Test fun pingElicitsPong() {
        val (bearer, radio, _) = makeBearer()
        bringLinkUp(bearer, radio)
        radio.clearSent()
        deliverFrame(bearer, radio, MeshFrame.ping(3L, 1000L))
        val pong = frameTo(peerNode, radio.sent)
        assertEquals(M_PONG, pong!![0].toInt() and 0xff)
    }

    @Test fun maintenanceReapsDeadLink() {
        val (bearer, radio, sink) = makeBearer()
        bringLinkUp(bearer, radio)
        val link = sink.ups[0].link
        bearer.runMaintenanceForTest(System.currentTimeMillis() + MESH_DEAD_MS + 5000)
        assertEquals(listOf(link), sink.downs)
        assertEquals(0, bearer.linkCountForTest())
    }

    @Test fun maintenancePingsLiveLink() {
        val (bearer, radio, _) = makeBearer()
        bringLinkUp(bearer, radio)
        radio.clearSent()
        bearer.runMaintenanceForTest(System.currentTimeMillis() + MESH_PING_MS + 1000)
        assertTrue(allFrames(radio.sent).any { it.first == peerNode && (it.second[0].toInt() and 0xff) == M_PING })
    }

    @Test fun stopTearsDownLinks() {
        val (bearer, radio, sink) = makeBearer()
        bringLinkUp(bearer, radio)
        bearer.stop()
        Thread.sleep(50)
        assertTrue(radio.stopped)
        assertEquals(1, sink.downs.size)
    }

    @Test fun radioDisconnectTearsDownLinks() {
        val (bearer, radio, sink) = makeBearer()
        bringLinkUp(bearer, radio)
        radio.fireDisconnect()
        bearer.awaitIdle()
        assertEquals(1, sink.downs.size)
        assertNull(bearer.myNodeNumForTest())
    }

    @Test fun selfHelloEchoIgnored() {
        val (bearer, radio, sink) = makeBearer()
        connect(bearer, radio)
        deliverFrame(bearer, radio, MeshFrame.hello(myId, false))
        assertEquals(0, bearer.linkCountForTest())
        assertTrue(sink.ups.isEmpty())
    }
}
