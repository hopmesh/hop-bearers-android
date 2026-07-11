package sh.hopme.bearers.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * cov/android-bearers: the pure BLE link wire logic (LinkProtocol), driven over ordinary in-memory
 * streams under Robolectric (android.util.Log is shadowed). These pin the framing + HELLO/PING/PONG/DATA
 * dispatch + reaper/liveness watchdog that were lifted out of the device-bound `Link` shell: exactly the
 * bytes on the wire and the exact lifecycle edges the on-device transport relies on, now testable with no
 * BluetoothSocket. The socket + rx-thread + keepalive-executor wiring stays in `Link` (device-covered).
 */
@RunWith(RobolectricTestRunner::class)
class LinkProtocolTest {

    private fun id16(v: Int) = ByteArray(16) { v.toByte() }

    private fun frame(body: ByteArray): ByteArray {
        val n = body.size
        return byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()) + body
    }
    private fun hello(id: ByteArray, dialer: Boolean) =
        frame(byteArrayOf(FRAME_HELLO.toByte()) + id + byteArrayOf((if (dialer) 1 else 0).toByte(), 0))
    private fun ping(seq: Long, now: Long) = frame(byteArrayOf(FRAME_PING.toByte()) + u64(seq) + u64(now))
    private fun data(payload: ByteArray) = frame(byteArrayOf(FRAME_DATA.toByte()) + payload)
    private fun u64(v: Long) = ByteArray(8) { (v ushr (56 - it * 8)).toByte() }

    /// Read the type tags of every length-prefixed frame in [bytes] (what LinkProtocol wrote to `out`).
    private fun frameTypes(bytes: ByteArray): List<Int> {
        val out = ArrayList<Int>()
        var i = 0
        while (i + 4 <= bytes.size) {
            val len = (bytes[i].u shl 24) or (bytes[i + 1].u shl 16) or (bytes[i + 2].u shl 8) or bytes[i + 3].u
            i += 4
            if (i + len > bytes.size || len < 1) break
            out.add(bytes[i].u)
            i += len
        }
        return out
    }
    private val Byte.u get() = toInt() and 0xff

    private class Sink {
        var up = false
        val data = ArrayList<ByteArray>()
        val closes = ArrayList<String>()
    }

    // ---- synchronous frame processing (prebuilt input, runReadLoop to eof) -------------------------

    @Test fun helloBringsLinkUpThenDataSurfacesThenEofCloses() {
        val input = hello(id16(0x22), dialer = false) + data("hello-payload".toByteArray())
        val out = ByteArrayOutputStream()
        val s = Sink()
        val p = LinkProtocol(
            out, ByteArrayInputStream(input), linkId = 1, isDialer = true, myId = id16(0xF0),
            onUp = { s.up = true }, onData = { s.data.add(it) }, onClosed = { s.closes.add(it) },
        )
        p.sendHello()
        p.runReadLoop() // processes HELLO + DATA, then ByteArrayInputStream eof -> close("read: eof")

        assertTrue("HELLO surfaces up", s.up)
        assertArrayEquals(id16(0x22), p.peerId)
        assertEquals(1, s.data.size)
        assertArrayEquals("hello-payload".toByteArray(), s.data[0])
        assertTrue("eof closes the link", s.closes.any { it.startsWith("read:") })
        assertTrue("our HELLO was written", frameTypes(out.toByteArray()).contains(FRAME_HELLO))
    }

    @Test fun pingIsAnsweredWithPong() {
        val input = hello(id16(0x22), false) + ping(seq = 5L, now = 111L) + ping(seq = 6L, now = 222L)
        val out = ByteArrayOutputStream()
        val s = Sink()
        val p = LinkProtocol(
            out, ByteArrayInputStream(input), 1, true, id16(0xF0),
            onUp = { s.up = true }, onData = { s.data.add(it) }, onClosed = { s.closes.add(it) },
        )
        p.runReadLoop()
        val types = frameTypes(out.toByteArray())
        assertTrue("each PING gets a PONG", types.count { it == FRAME_PONG } >= 2)
    }

    @Test fun malformedLengthClosesLink() {
        val out = ByteArrayOutputStream()
        val s = Sink()
        val badLen = byteArrayOf(0, 0, 0, 0) // declared length 0 is rejected (< 1)
        val p = LinkProtocol(
            out, ByteArrayInputStream(badLen), 1, false, id16(0xF0),
            onUp = { s.up = true }, onData = { s.data.add(it) }, onClosed = { s.closes.add(it) },
        )
        p.runReadLoop()
        assertTrue("bad length closes", s.closes.any { it.startsWith("bad len") })
    }

    @Test fun shortHelloDoesNotBringUp() {
        val shortHello = frame(byteArrayOf(FRAME_HELLO.toByte()) + ByteArray(8)) // < 17 bytes
        val out = ByteArrayOutputStream()
        val s = Sink()
        val p = LinkProtocol(
            out, ByteArrayInputStream(shortHello), 1, false, id16(0xF0),
            onUp = { s.up = true }, onData = { s.data.add(it) }, onClosed = { s.closes.add(it) },
        )
        p.runReadLoop()
        assertFalse("a truncated HELLO must not bring the link up", s.up)
    }

    @Test fun unknownFrameAndPongAreIgnored() {
        val input = hello(id16(0x22), false) +
            frame(byteArrayOf(0x77, 1, 2)) +                              // unknown type
            frame(byteArrayOf(FRAME_PONG.toByte(), 9)) +                 // PONG (reverse liveness only)
            data("after".toByteArray())
        val out = ByteArrayOutputStream()
        val s = Sink()
        val p = LinkProtocol(
            out, ByteArrayInputStream(input), 1, true, id16(0xF0),
            onUp = { s.up = true }, onData = { s.data.add(it) }, onClosed = { s.closes.add(it) },
        )
        p.runReadLoop()
        assertEquals("unknown + PONG ignored; DATA still surfaces", 1, s.data.size)
        assertArrayEquals("after".toByteArray(), s.data[0])
    }

    @Test fun nonConsecutivePingSeqLogsACounterGap() {
        // A jump in the peer's keepalive counter (5 -> 9, not 5 -> 6) hits the counter-gap branch; both
        // PINGs are still answered with a PONG (the gap is only logged, never fatal).
        val input = hello(id16(0x22), false) + ping(seq = 5L, now = 1L) + ping(seq = 9L, now = 2L)
        val out = ByteArrayOutputStream()
        val s = Sink()
        val p = LinkProtocol(
            out, ByteArrayInputStream(input), 1, true, id16(0xF0),
            onUp = { s.up = true }, onData = { s.data.add(it) }, onClosed = { s.closes.add(it) },
        )
        p.runReadLoop()
        assertEquals("both PINGs answered despite the seq gap", 2, frameTypes(out.toByteArray()).count { it == FRAME_PONG })
    }

    @Test fun shortPingIsIgnored() {
        // A PING body shorter than 9 bytes is dropped (mirrors Apple's guard) - no PONG, no crash.
        val input = hello(id16(0x22), false) + frame(byteArrayOf(FRAME_PING.toByte(), 1, 2))
        val out = ByteArrayOutputStream()
        val s = Sink()
        val p = LinkProtocol(
            out, ByteArrayInputStream(input), 1, true, id16(0xF0),
            onUp = { s.up = true }, onData = { s.data.add(it) }, onClosed = { s.closes.add(it) },
        )
        p.runReadLoop()
        assertFalse("a malformed short PING yields no PONG", frameTypes(out.toByteArray()).contains(FRAME_PONG))
    }

    // ---- tick(): reaper / keepalive / liveness watchdog, via an injected clock -------------------

    @Test fun tickReapsAHalfOpenLink() {
        val clock = AtomicLong(1_000L)
        val out = ByteArrayOutputStream()
        val s = Sink()
        val p = LinkProtocol(
            out, ByteArrayInputStream(ByteArray(0)), 1, true, id16(0xF0),
            onUp = { s.up = true }, onData = { s.data.add(it) }, onClosed = { s.closes.add(it) },
            clock = { clock.get() },
        )
        clock.set(1_000L + REAP_MS + 1) // never received a HELLO within the reap window
        p.tick()
        assertTrue("half-open link is reaped", s.closes.any { it.contains("reap") })
    }

    @Test fun tickEmitsKeepalivePingWhileUp() {
        withUpLink { p, out, _, clock ->
            val before = frameTypes(out.toByteArray()).count { it == FRAME_PING }
            clock.addAndGet(1_000L)
            p.tick() // within the liveness deadline -> a keepalive PING
            val after = frameTypes(out.toByteArray()).count { it == FRAME_PING }
            assertTrue("tick emits a keepalive PING", after > before)
        }
    }

    @Test fun tickTripsLivenessWatchdogWhenSilent() {
        withUpLink { p, _, s, clock ->
            clock.addAndGet(DEAD_MS + 10_000L) // no rx for well past the foreground deadline
            p.tick()
            assertTrue("a silent link trips the liveness watchdog", s.closes.any { it.contains("DEAD") })
        }
    }

    @Test fun backgroundExtendsTheLivenessDeadline() {
        // In background the deadline is DEAD_BG_MS; a gap between DEAD_MS and DEAD_BG_MS does NOT trip.
        withUpLink(background = true) { p, _, s, clock ->
            clock.addAndGet(DEAD_MS + 1_000L) // > foreground deadline, < background deadline
            p.tick()
            assertTrue("background link survives past the foreground deadline", s.closes.isEmpty())
        }
    }

    @Test fun stableUpAfterThirtySeconds() {
        withUpLink { p, _, _, clock ->
            assertFalse("not stable immediately", p.stableUp())
            clock.addAndGet(30_000L)
            assertTrue("stable after 30s up", p.stableUp())
        }
    }

    // ---- sendData / close idempotency + write-failure -------------------------------------------

    @Test fun sendDataWritesADataFrameAndIsNoOpAfterClose() {
        val out = ByteArrayOutputStream()
        val s = Sink()
        val p = LinkProtocol(
            out, ByteArrayInputStream(ByteArray(0)), 1, true, id16(0xF0),
            onUp = { s.up = true }, onData = { s.data.add(it) }, onClosed = { s.closes.add(it) },
        )
        p.sendData("x".toByteArray())
        assertTrue(frameTypes(out.toByteArray()).contains(FRAME_DATA))
        val len = out.size()
        p.close("done")
        p.close("again") // idempotent: no second onClosed
        p.sendData("y".toByteArray()) // no-op after close
        assertEquals("send after close writes nothing", len, out.size())
        assertEquals("close is idempotent", 1, s.closes.size)
    }

    @Test fun writeFailureClosesTheLink() {
        val throwing = object : OutputStream() {
            override fun write(b: Int) { throw IOException("boom") }
            override fun write(b: ByteArray) { throw IOException("boom") }
            override fun write(b: ByteArray, off: Int, len: Int) { throw IOException("boom") }
        }
        val s = Sink()
        val p = LinkProtocol(
            throwing, ByteArrayInputStream(ByteArray(0)), 1, true, id16(0xF0),
            onUp = { s.up = true }, onData = { s.data.add(it) }, onClosed = { s.closes.add(it) },
        )
        p.sendHello() // the write throws -> close("write: boom")
        assertTrue("a write failure closes the link", s.closes.any { it.startsWith("write:") })
    }

    @Test fun peerIdIsNullBeforeHello() {
        val p = LinkProtocol(
            ByteArrayOutputStream(), ByteArrayInputStream(ByteArray(0)), 1, true, id16(0xF0),
            onUp = {}, onData = {}, onClosed = {},
        )
        assertNull(p.peerId)
        assertFalse(p.up)
        assertFalse(p.isClosed)
    }

    /// Bring a link UP over a pipe (so runReadLoop stays blocked, not eof), then hand the test a live
    /// protocol + its captured output + an injectable clock to drive tick() deterministically.
    private fun withUpLink(
        background: Boolean = false,
        body: (LinkProtocol, ByteArrayOutputStream, Sink, AtomicLong) -> Unit,
    ) {
        val clock = AtomicLong(1_000L)
        val peerOut = PipedOutputStream()
        val inp: InputStream = PipedInputStream(peerOut, 4096)
        val out = ByteArrayOutputStream()
        val s = Sink()
        // Foreground path uses the DEFAULT background arg ({ appInBackground }, which is false) so the
        // real default is exercised; the background path injects an explicit true.
        val p = if (background) {
            LinkProtocol(
                out, inp, linkId = 1, isDialer = true, myId = id16(0xF0),
                onUp = { s.up = true }, onData = { s.data.add(it) },
                onClosed = { s.closes.add(it); runCatching { peerOut.close() } },
                clock = { clock.get() }, background = { true },
            )
        } else {
            LinkProtocol(
                out, inp, linkId = 1, isDialer = true, myId = id16(0xF0),
                onUp = { s.up = true }, onData = { s.data.add(it) },
                onClosed = { s.closes.add(it); runCatching { peerOut.close() } },
                clock = { clock.get() },
            )
        }
        val rx = thread(name = "test-rx") { p.runReadLoop() }
        peerOut.write(hello(id16(0x22), dialer = false)); peerOut.flush()
        val end = System.currentTimeMillis() + 3000
        while (!s.up && System.currentTimeMillis() < end) Thread.sleep(5)
        assertTrue("link came up over the pipe", s.up)
        try {
            body(p, out, s, clock)
        } finally {
            p.close("test-teardown")
            runCatching { peerOut.close() }
            rx.join(1000)
        }
    }
}
