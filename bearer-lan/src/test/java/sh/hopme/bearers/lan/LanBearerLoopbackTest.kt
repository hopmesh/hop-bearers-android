package sh.hopme.bearers.lan

import android.content.Context
import android.net.nsd.NsdServiceInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import sh.hop.HopRole
import sh.hop.LinkSink
import sh.hop.toHex
import java.io.EOFException
import java.net.InetAddress
import java.net.Socket

/**
 * cov/android-bearers: real loopback-socket integration tests of the LanBearer/LanLink transport, run
 * under Robolectric so android.util.Log is shadowed (LanLink logs on every lifecycle edge). These drive
 * the REAL production code over 127.0.0.1 TCP: the accept + dial legs, the HELLO/PING/PONG/DATA framing
 * and keepalive, the one-pipe-per-peer dedup survivor, linkUp/linkBytes/linkDown pairing, the discovery
 * gate (skip-self / greater-id-dials / in-flight dedup / already-linked), restart, and error paths
 * (bad length, short HELLO, unknown frame, dial to a dead port). The only excluded surface is the NSD
 * Registration/Discovery/Resolve listener callbacks, which the platform daemon delivers and Robolectric
 * cannot fire (documented in build.gradle.kts).
 */
@RunWith(RobolectricTestRunner::class)
class LanBearerLoopbackTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val bearers = ArrayList<LanBearer>()
    private val raws = ArrayList<RawPeer>()

    @After fun tearDown() {
        raws.forEach { runCatching { it.close() } }
        bearers.forEach { runCatching { it.stop() } }
    }

    private class Rec : LinkSink {
        val ups = ArrayList<Triple<Long, HopRole, String>>()
        val bytes = ArrayList<Pair<Long, ByteArray>>()
        val downs = ArrayList<Long>()
        @Synchronized override fun linkUp(link: Long, role: HopRole, peerId: ByteArray) {
            ups.add(Triple(link, role, peerId.toHex()))
        }
        @Synchronized override fun linkBytes(link: Long, b: ByteArray) { bytes.add(link to b) }
        @Synchronized override fun linkDown(link: Long) { downs.add(link) }
        @Synchronized fun upCount() = ups.size
        @Synchronized fun downCount() = downs.size
        @Synchronized fun dataFor(link: Long) = bytes.filter { it.first == link }.map { it.second }
    }

    /// A hand-rolled loopback peer that speaks the LAN wire grammar directly, so a test can drive
    /// LanLink.handle deterministically (PING/PONG, bad length, short HELLO, unknown frame) without a
    /// second bearer or the real 1 Hz keepalive timing.
    private inner class RawPeer(port: Int) {
        private val sock = Socket("127.0.0.1", port)
        private val out = sock.getOutputStream()
        private val inp = sock.getInputStream()
        init { raws.add(this) }
        fun sendFrame(body: ByteArray) { out.write(LanWire.encodeFrame(body)); out.flush() }
        fun sendRaw(bytes: ByteArray) { out.write(bytes); out.flush() }
        fun sendHello(id: ByteArray, dialer: Boolean) = sendFrame(LanWire.hello(id, dialer))
        private fun readN(n: Int): ByteArray {
            val b = ByteArray(n); var o = 0
            while (o < n) { val r = inp.read(b, o, n - o); if (r < 0) throw EOFException(); o += r }
            return b
        }
        fun readFrame(): LanFrame = LanWire.decodeBody(readN(LanWire.readLength(readN(4))))!!
        /// Read frames until one of [type] arrives (skips the bearer's own HELLO / keepalive PING).
        fun readFrameOfType(type: Int): LanFrame {
            repeat(8) { val f = readFrame(); if (f.type == type) return f }
            error("frame type $type not seen")
        }
        fun close() = runCatching { sock.close() }
    }

    private fun waitUntil(ms: Long = 5000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(15) }
        return cond()
    }

    private fun startedBearer(id: ByteArray, sink: Rec): LanBearer {
        val b = LanBearer(ctx, id).also { it.sink = sink }
        bearers.add(b)
        b.start()
        assertTrue("listener bound", waitUntil { b.boundPort != 0 })
        return b
    }

    private fun fill(v: Int) = ByteArray(16) { v.toByte() }
    private fun svcFor(id: ByteArray, port: Int) = NsdServiceInfo().apply {
        serviceName = id.toHex(); serviceType = LAN_SERVICE_TYPE
        host = InetAddress.getByName("127.0.0.1"); this.port = port
    }

    // ---- two-node loopback: real dial + accept, data both ways, clean stop -------------------------

    @Test fun twoNodesLinkAndExchangeDataBothWays() {
        val recA = Rec(); val recB = Rec()
        val a = startedBearer(fill(0xF0), recA) // greater
        val b = startedBearer(fill(0x01), recB) // lesser
        a.dialForTest(svcFor(fill(0x01), b.boundPort))

        assertTrue("A linkUp", waitUntil { recA.upCount() == 1 })
        assertTrue("B linkUp", waitUntil { recB.upCount() == 1 })
        assertEquals(HopRole.DIALER, recA.ups.first().second)
        assertEquals(HopRole.ACCEPTOR, recB.ups.first().second)

        val la = recA.ups.first().first
        val lb = recB.ups.first().first
        a.send("a->b".toByteArray(), la)
        b.send("b->a".toByteArray(), lb)
        assertTrue("B got a->b", waitUntil { recB.dataFor(lb).any { String(it) == "a->b" } })
        assertTrue("A got b->a", waitUntil { recA.dataFor(la).any { String(it) == "b->a" } })

        // Let the 1 Hz keepalive exchange at least one PING/PONG round on both legs.
        Thread.sleep(1200)
        a.stop()
        assertTrue("A linkDown after stop", waitUntil { recA.downCount() >= 1 })
    }

    // ---- mutual dial → dedup keeps exactly one survivor (both ends agree) --------------------------

    @Test fun mutualDialDedupKeepsOneSurvivor() {
        val recA = Rec(); val recB = Rec()
        val a = startedBearer(fill(0xF0), recA) // greater
        val b = startedBearer(fill(0x01), recB) // lesser
        // Each dials the other → each side ends with one dialed + one accepted leg to the same peer.
        a.dialForTest(svcFor(fill(0x01), b.boundPort))
        b.dialForTest(svcFor(fill(0xF0), a.boundPort))
        // Both legs surface linkUp, then dedup closes the loser on each side → one linkDown each.
        assertTrue("A saw both legs up then one down", waitUntil { recA.upCount() >= 2 && recA.downCount() >= 1 })
        assertTrue("B saw both legs up then one down", waitUntil { recB.upCount() >= 2 && recB.downCount() >= 1 })
        // Exactly one survivor per side (up - down == 1).
        assertTrue(waitUntil { recA.upCount() - recA.downCount() == 1 })
        assertTrue(waitUntil { recB.upCount() - recB.downCount() == 1 })
    }

    // ---- raw peer drives LanLink.handle deterministically -----------------------------------------

    @Test fun rawPeerPingGetsPong() {
        val rec = Rec()
        val a = startedBearer(fill(0x80), rec)
        val raw = RawPeer(a.boundPort)
        raw.sendHello(fill(0x22), dialer = true)
        assertTrue("bearer linkUp on HELLO", waitUntil { rec.upCount() == 1 })
        raw.sendFrame(LanWire.ping(seq = 7L, nowMs = 123L))
        val pong = raw.readFrameOfType(L_PONG)
        assertEquals(L_PONG, pong.type)
    }

    @Test fun rawPeerDataSurfacesAsLinkBytes() {
        val rec = Rec()
        val a = startedBearer(fill(0x80), rec)
        val raw = RawPeer(a.boundPort)
        raw.sendHello(fill(0x22), dialer = true)
        assertTrue(waitUntil { rec.upCount() == 1 })
        val link = rec.ups.first().first
        raw.sendFrame(LanWire.data("payload".toByteArray()))
        assertTrue(waitUntil { rec.dataFor(link).any { String(it) == "payload" } })
    }

    @Test fun rawPeerBadLengthClosesLink() {
        val rec = Rec()
        val a = startedBearer(fill(0x80), rec)
        val raw = RawPeer(a.boundPort)
        raw.sendHello(fill(0x22), dialer = true)
        assertTrue(waitUntil { rec.upCount() == 1 })
        val link = rec.ups.first().first
        raw.sendRaw(byteArrayOf(0, 0, 0, 0)) // declared length 0 is invalid → close("bad len 0")
        assertTrue("bad-length frame closes the link", waitUntil { rec.downs.contains(link) })
    }

    @Test fun rawPeerShortHelloDoesNotLinkUp() {
        val rec = Rec()
        val a = startedBearer(fill(0x80), rec)
        val raw = RawPeer(a.boundPort)
        raw.sendFrame(byteArrayOf(L_HELLO.toByte()) + ByteArray(8)) // < 17 bytes: guard rejects
        raw.sendFrame(LanWire.ping(1L, 1L)) // a valid follow-up frame the read loop consumes
        Thread.sleep(300)
        assertEquals("a truncated HELLO must not surface linkUp", 0, rec.upCount())
    }

    @Test fun rawPeerUnknownFrameIsIgnored() {
        val rec = Rec()
        val a = startedBearer(fill(0x80), rec)
        val raw = RawPeer(a.boundPort)
        raw.sendHello(fill(0x22), dialer = true)
        assertTrue(waitUntil { rec.upCount() == 1 })
        raw.sendFrame(byteArrayOf(0x77, 1, 2, 3)) // unknown type → ignored, link stays up
        raw.sendFrame(LanWire.data("still-here".toByteArray()))
        val link = rec.ups.first().first
        assertTrue(waitUntil { rec.dataFor(link).any { String(it) == "still-here" } })
        assertEquals("unknown frame must not tear the link down", 0, rec.downCount())
    }

    // ---- discovery gate (skip-self / greater-id-dials / in-flight dedup / already-linked) ----------

    @Test fun discoveryGateSkipsSelfAndGatesOnId() {
        // Drive the discovery gate on a NON-started bearer: with no NsdManager registered, enqueueResolve
        // -> pumpResolve short-circuits at `nsd ?: return` instead of calling the platform resolveService
        // (which Robolectric's NSD shadow cannot complete). This exercises handleServiceFound's gating +
        // the resolve queue plumbing purely, with no radio.
        val rec = Rec()
        val myId = fill(0x80)
        val a = LanBearer(ctx, myId).also { it.sink = rec }
        bearers.add(a)
        // Self advert: name == my own hex → ignored (no dial, no crash).
        a.onDiscoveredForTest(svcFor(myId, 1))
        // A peer GREATER than me: remembered for rescan but I do NOT dial (the lesser side listens).
        a.onDiscoveredForTest(svcFor(fill(0xFF), 2))
        // A peer LESSER than me: I dial → dialing.add + enqueueResolve + pumpResolve (nsd-null no-op).
        a.onDiscoveredForTest(svcFor(fill(0x01), 3))
        // Same lesser peer again while its dial is in flight → the in-flight dedup returns early.
        a.onDiscoveredForTest(svcFor(fill(0x01), 3))
        // Malformed instance name → parsed to null → ignored.
        a.onDiscoveredForTest(NsdServiceInfo().apply { serviceName = "not-hex"; serviceType = LAN_SERVICE_TYPE })
        assertEquals("discovery gating never surfaces a link on its own", 0, rec.upCount())
    }

    @Test fun alreadyLinkedPeerIsNotReDialed() {
        val recA = Rec(); val recB = Rec()
        val a = startedBearer(fill(0xF0), recA) // greater
        val b = startedBearer(fill(0x01), recB) // lesser
        a.dialForTest(svcFor(fill(0x01), b.boundPort))
        assertTrue(waitUntil { recA.upCount() == 1 })
        // Now B is discovered again: linksByPeerId already holds it → the gate returns before dialing.
        a.onDiscoveredForTest(svcFor(fill(0x01), b.boundPort))
        Thread.sleep(200)
        assertEquals("an already-linked peer is not re-dialed into a second link", 1, recA.upCount())
    }

    @Test fun rescanRunsWithoutAKnownPeer() {
        val rec = Rec()
        val a = startedBearer(fill(0x80), rec)
        a.rescanForTest() // empty knownServices: the loop is a no-op, but the entry/stopped-guard runs
        assertEquals(0, rec.upCount())
    }

    // ---- send routing + dial failure + restart ----------------------------------------------------

    @Test fun sendToUnknownLinkIsNoOp() {
        val rec = Rec()
        val a = startedBearer(fill(0x80), rec)
        a.send(byteArrayOf(1, 2, 3), 999L) // no such link → dropped, no throw
        assertEquals(0, rec.upCount())
    }

    @Test fun dialToDeadPortClearsDialingWithoutLink() {
        val rec = Rec()
        val a = startedBearer(fill(0x80), rec)
        // A lesser peer at a port nobody is listening on → connect fails → dialing cleared, no link.
        val deadPort = a.boundPort + 1
        a.dialForTest(svcFor(fill(0x01), deadPort))
        Thread.sleep(500)
        assertEquals("a failed dial forms no link", 0, rec.upCount())
    }

    @Test fun bearerRestartsAfterStopAndCanRelink() {
        // Regression: retryExec was a `val` shut down on stop(), so start() after stop() threw
        // RejectedExecutionException on its rescan schedule and the bearer was permanently dead. A
        // BearerManager disable→enable calls stop() then start() on the same instance, so restart must
        // work: reinstall a fresh executor, re-bind a listener, and form a fresh link.
        val recA = Rec()
        val a = startedBearer(fill(0xF0), recA) // greater
        a.stop()
        a.start() // must NOT throw; reinstalls the rescan executor
        assertTrue("a restarted bearer re-binds a listener", waitUntil { a.boundPort != 0 })
        // And it can form a brand-new link after the restart.
        val recB = Rec()
        val b = startedBearer(fill(0x01), recB) // lesser
        a.dialForTest(svcFor(fill(0x01), b.boundPort))
        assertTrue("restarted bearer forms a fresh link", waitUntil { recA.upCount() >= 1 })
        assertTrue(waitUntil { recB.upCount() >= 1 })
    }

    @Test fun dialFromLesserPeerStillFormsLinkOverLoopback() {
        // dialForTest bypasses the greater-id gate (it is the post-resolve leg), so even a lesser-id
        // bearer forms the socket when told to - proving the dial/accept plumbing is id-independent.
        val recA = Rec(); val recB = Rec()
        val a = startedBearer(fill(0x01), recA) // lesser
        val b = startedBearer(fill(0xF0), recB) // greater
        a.dialForTest(svcFor(fill(0xF0), b.boundPort))
        assertTrue("lesser-id dialer still forms the link", waitUntil { recA.upCount() == 1 })
        assertTrue("greater-id acceptor still comes up", waitUntil { recB.upCount() == 1 })
        assertEquals(HopRole.DIALER, recA.ups.first().second)
    }
}
