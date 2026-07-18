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
import java.net.SocketTimeoutException

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
        fun peerClosed(): Boolean {
            sock.soTimeout = 50
            return try { inp.read() < 0 } catch (_: SocketTimeoutException) { false }
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

    /// The link id (from `ups`) that never got a matching `down`, plus the role it surfaced with.
    private fun survivorRole(rec: Rec): HopRole {
        val survivorId = rec.ups.map { it.first }.first { it !in rec.downs }
        return rec.ups.first { it.first == survivorId }.second
    }

    // ---- F-3: pin the dedup keep-rule's arrival order so BOTH LanBearer.onUp() dispatch slots ------
    // (DedupKeep.EXISTING / DedupKeep.INCOMING) are hit EVERY run, not just whichever one the real
    // socket race happens to land on.
    //
    // mutualDialDedupKeepsOneSurvivor above starts both dials back-to-back with no ordering between
    // them, so on each side, whichever of "my dial leg" / "my accept leg" completes its HELLO round
    // trip first becomes `existing` in onUp()'s dedup map and the other becomes `incoming` - a genuine
    // race between two independently-established TCP connections that depends on real thread/socket
    // scheduling. LanDedup.decide() itself is a pure function and is already fully+deterministically
    // covered above (LanWireTest / LanFrameDedupTest exercise every input combination directly), but
    // the CALL SITE in onUp() (LanBearer.kt, the `when (LanDedup.decide(...)) { EXISTING -> ...;
    // INCOMING -> ... }` dispatch) only runs through this integration path, so ITS branch coverage
    // rode on that race: some CI runs happened to land EXISTING on both sides, some INCOMING on both,
    // some a mix - flipping the covered line/branch set 94.4% vs 94.8% run to run (F-3).
    //
    // Fix: force the arrival order explicitly by waiting for the FIRST connection to be fully up on
    // BOTH ends before starting the SECOND one, so `existing` is deterministically known before the
    // dedup ever runs. Two tests, one per order, guarantee both slots are exercised every single run -
    // and additionally assert the actual survivor identity (which the racy test above never checked),
    // proving the keep-rule always preserves the SAME physical connection (the greater peer's dial)
    // regardless of which slot the decision passed through.

    @Test fun mutualDialConnection1FirstResolvesViaExistingSlot() {
        val recA = Rec(); val recB = Rec()
        val a = startedBearer(fill(0xF0), recA) // greater
        val b = startedBearer(fill(0x01), recB) // lesser
        // Connection 1 (A dials B) is fully up on BOTH ends before Connection 2 starts, so it is
        // deterministically `existing` in onUp()'s dedup map on both sides when Connection 2 arrives.
        a.dialForTest(svcFor(fill(0x01), b.boundPort))
        assertTrue("A's dial leg up first", waitUntil { recA.upCount() == 1 })
        assertTrue("B's accept leg up first", waitUntil { recB.upCount() == 1 })
        // Connection 2 (B dials A) now arrives second on both sides -> greater-id A already holds its
        // DIALED leg as `existing` (matches keepDialed) -> DedupKeep.EXISTING; lesser-id B already
        // holds its ACCEPTED leg as `existing` (matches keepDialed=false) -> DedupKeep.EXISTING too.
        b.dialForTest(svcFor(fill(0xF0), a.boundPort))
        assertTrue("A saw both legs then dedup", waitUntil { recA.upCount() == 2 && recA.downCount() == 1 })
        assertTrue("B saw both legs then dedup", waitUntil { recB.upCount() == 2 && recB.downCount() == 1 })
        // The survivor is Connection 1 in both cases: A keeps its dial, B keeps its accept.
        assertEquals("greater id's dialed leg survives", HopRole.DIALER, survivorRole(recA))
        assertEquals("lesser id's accepted leg survives", HopRole.ACCEPTOR, survivorRole(recB))
    }

    @Test fun mutualDialConnection2FirstResolvesViaIncomingSlot() {
        val recA = Rec(); val recB = Rec()
        val a = startedBearer(fill(0xF0), recA) // greater
        val b = startedBearer(fill(0x01), recB) // lesser
        // Reverse the arrival order: Connection 2 (B dials A) is fully up on both ends FIRST, so it is
        // deterministically `existing`; Connection 1 (A dials B) arrives second and is `incoming` ->
        // the dedup keep-rule takes the INCOMING slot on both ends this time - the sibling branch F-3
        // found flaky.
        b.dialForTest(svcFor(fill(0xF0), a.boundPort))
        assertTrue("B's dial leg up first", waitUntil { recB.upCount() == 1 })
        assertTrue("A's accept leg up first", waitUntil { recA.upCount() == 1 })
        a.dialForTest(svcFor(fill(0x01), b.boundPort))
        assertTrue("A saw both legs then dedup", waitUntil { recA.upCount() == 2 && recA.downCount() == 1 })
        assertTrue("B saw both legs then dedup", waitUntil { recB.upCount() == 2 && recB.downCount() == 1 })
        // Same physical survivor regardless of arrival order: A's dial / B's accept (Connection 1),
        // reached this time via the INCOMING slot instead of EXISTING.
        assertEquals("greater id's dialed leg still survives", HopRole.DIALER, survivorRole(recA))
        assertEquals("lesser id's accepted leg still survives", HopRole.ACCEPTOR, survivorRole(recB))
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

    @Test fun pendingSlowPeersStopAtCapAndCleanupAdmitsAValidPeer() {
        val rec = Rec()
        val a = startedBearer(fill(0x80), rec)
        val slow = List(LAN_MAX_PENDING_LINKS) { RawPeer(a.boundPort) }
        assertTrue("all cap slots admitted", waitUntil { a.pendingLinkCount == LAN_MAX_PENDING_LINKS })

        val overflow = RawPeer(a.boundPort)
        assertTrue("cap+1 socket is closed by the bearer", waitUntil { overflow.peerClosed() })
        assertEquals("cap+1 is closed without an admission lease", LAN_MAX_PENDING_LINKS, a.pendingLinkCount)

        slow.first().close()
        assertTrue("closing one hostile peer releases capacity", waitUntil { a.pendingLinkCount == LAN_MAX_PENDING_LINKS - 1 })
        val valid = RawPeer(a.boundPort)
        valid.sendHello(fill(0x33), dialer = true)
        assertTrue("a valid peer links after hostile cleanup", waitUntil { rec.upCount() == 1 })
    }

    @Test fun aggregatePreauthBytesRejectBeforeFifthMaxFrameAllocationAndRecover() {
        val rec = Rec()
        val a = startedBearer(fill(0x80), rec)
        val holding = List(4) { RawPeer(a.boundPort) }
        holding.forEach { it.sendRaw(LanWire.lengthPrefix(LAN_MAX_FRAME)) }
        assertTrue(
            "four incomplete frames consume the aggregate byte budget",
            waitUntil { a.retainedPreauthBytes == LAN_MAX_PREAUTH_BYTES_TOTAL },
        )

        val rejected = RawPeer(a.boundPort)
        rejected.sendRaw(LanWire.lengthPrefix(LAN_MAX_FRAME))
        assertTrue("aggregate exhaustion closes cap+1 allocation", waitUntil { rejected.peerClosed() })
        assertEquals(holding.size, a.pendingLinkCount)

        holding.forEach { it.close() }
        assertTrue("hostile frame cleanup releases every byte", waitUntil { a.retainedPreauthBytes == 0 && a.pendingLinkCount == 0 })
        val valid = RawPeer(a.boundPort)
        valid.sendHello(fill(0x44), dialer = true)
        assertTrue(waitUntil { rec.upCount() == 1 })
    }

    @Test fun oversizedAnnouncedFrameClosesBeforeBodyAndNextPeerLinks() {
        val rec = Rec()
        val a = startedBearer(fill(0x80), rec)
        val hostile = RawPeer(a.boundPort)
        hostile.sendRaw(LanWire.lengthPrefix(LAN_MAX_FRAME + 1))
        assertTrue("oversized declared body closes before body bytes", waitUntil { hostile.peerClosed() })
        assertTrue("oversized declared length releases its link", waitUntil { a.pendingLinkCount == 0 })
        assertEquals(0, a.retainedPreauthBytes)

        val valid = RawPeer(a.boundPort)
        valid.sendHello(fill(0x55), dialer = true)
        assertTrue(waitUntil { rec.upCount() == 1 })
    }

    @Test fun noHelloTimeoutReleasesAdmissionForNextPeer() {
        val rec = Rec()
        val a = startedBearer(fill(0x80), rec)
        RawPeer(a.boundPort)
        assertTrue(waitUntil { a.pendingLinkCount == 1 })
        assertTrue("the shared scheduler reaps a stalled preauth link", waitUntil(7000) { a.pendingLinkCount == 0 })

        val valid = RawPeer(a.boundPort)
        valid.sendHello(fill(0x66), dialer = true)
        assertTrue(waitUntil { rec.upCount() == 1 })
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
