package sh.hopme.bearers.ble

import sh.hop.TAG
import sh.hop.appInBackground
import sh.hop.toHex
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

// LinkProtocol - the BLE link's framing + HELLO/PING/PONG/DATA dispatch + keepalive/liveness logic,
// lifted out of `Link` (BleBearer.kt) so it can be exercised over ordinary InputStream/OutputStream on
// a plain JVM instead of a real BluetoothSocket. Same split as DialBackoff.kt / BleDedup.kt: the pure,
// unit-testable core lives here; the device-bound shell (the BluetoothSocket, the rx thread, the
// keepalive ScheduledExecutor, socket.close()) stays in `Link`, which now just drives this.
//
// BEHAVIOR-PRESERVING: the bytes on the wire, the 4-byte BE framing, the reaper/liveness deadlines
// (REAP_MS / DEAD_MS / DEAD_BG_MS, adaptive via the rx EWMA), the HELLO identity handshake, and the
// PING->PONG keepalive are byte-for-byte and edge-for-edge identical to the previous inline `Link`. The
// clock and background flag are injectable purely so a test can drive the timing deterministically; both
// default to the exact production sources (System.currentTimeMillis / appInBackground).
internal class LinkProtocol(
    private val out: OutputStream,
    private val inp: InputStream,
    val linkId: Long,
    val isDialer: Boolean,
    private val myId: ByteArray,
    private val onUp: () -> Unit,
    private val onData: (ByteArray) -> Unit,
    private val onClosed: (String) -> Unit,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val background: () -> Boolean = { appInBackground },
) {
    @Volatile
    var peerId: ByteArray? = null

    @Volatile
    var up = false

    @Volatile
    private var becameUpMs = 0L

    @Volatile
    private var lastRxMs = clock()
    private val openedMs = clock()
    private var ewmaGapMs = 1000.0
    private var txSeq = 0L
    private var rxSeq = 0L
    private var rxBytes = 0L
    private var txBytes = 0L

    @Volatile
    private var closed = false
    val isClosed: Boolean get() = closed
    private val writeLock = Any()

    // ---- outbound queue (R-02) ------------------------------------------------------------------
    //
    // Writes used to go straight to the L2CAP socket on the CALLER's thread. The caller is the
    // driver's `pump()`, which runs on the single serial `hop.core` HandlerThread that owns ALL node
    // state. So one BLE peer whose receive window was full blocked that thread inside `out.write`,
    // and with it every other link (BLE, LAN, relay), all inbound processing, and `tick()` itself:
    // retransmits, prekey rotation and OPK reaping all stall behind an unrelated stranger's radio.
    // That couples node liveness to the least reliable component in the system.
    //
    // Frames are now enqueued and drained by the link's own writer thread, so `sendData` never
    // blocks the core. The queue is BOUNDED: a peer that cannot drain is not a usable link, and an
    // unbounded queue would just convert head-of-line blocking into unbounded memory growth.
    private val txQueue = LinkedBlockingQueue<ByteArray>()

    /// Incremented by the CALLER (`hop.core` via sendData) and decremented by the writer thread, so
    /// it is genuinely concurrent and a `@Volatile Long` would lose updates under a read-modify-write
    /// race. The consequence would be a slow drift in the backpressure threshold, which is exactly
    /// the kind of bug that only shows up under the load this cap exists to survive.
    private val txQueuedBytes = AtomicLong(0)

    // §6: a link is "stable" once it has stayed UP for >= 30 s.
    fun stableUp(): Boolean =
        up && becameUpMs != 0L && clock() - becameUpMs >= 30_000L

    /// HELLO first (SPEC §3.3): [0x01][16B nodeId][1B role][1B flags].
    fun sendHello() {
        sendFrame(byteArrayOf(FRAME_HELLO.toByte()) + myId + byteArrayOf((if (isDialer) 1 else 0).toByte(), 0))
        Log.i(TAG, "LINK OPENING link=$linkId isDialer=$isDialer reaper=${REAP_MS}ms - sent HELLO")
    }

    private fun deadLimit(): Long { // R7: adaptive deadline
        val base = if (background()) DEAD_BG_MS else DEAD_MS
        return maxOf(base, (3.0 * ewmaGapMs).toLong())
    }

    /// One keepalive tick (the bearer schedules this at 1 Hz). Reaps a half-open link, trips the liveness
    /// watchdog, else emits the next monotonic PING.
    fun tick() {
        val now = clock()
        if (!up && now - openedMs > REAP_MS) {
            close("no-HELLO reap")
            return
        }
        if (up && now - lastRxMs > deadLimit()) {
            close("liveness DEAD (silent ${now - lastRxMs}ms > ${deadLimit()}ms)")
            return
        }
        txSeq++
        sendFrame(byteArrayOf(FRAME_PING.toByte()) + u64(txSeq) + u64(now))
    }

    /// Bearer.send entry point: wrap the consumer's application bytes in a DATA frame (0x10) and send.
    /// Bearer.send path: ENQUEUE, never write. This is the ONLY sender reached from the driver's
    /// `pump()` on the serial `hop.core` thread, so it is the only one that must not block (see the
    /// txQueue note above). Control frames (HELLO/PING/PONG) are emitted from the link's OWN threads,
    /// where a blocking write harms nothing, so they stay synchronous: queueing them would change
    /// close-time semantics and buy nothing. Frames are self-delimiting, so DATA interleaving with a
    /// control frame under `writeLock` is safe and order between the two classes does not matter.
    ///
    /// Over the queue cap the link is CLOSED rather than silently dropping frames: a dropped DATA
    /// frame turns the link into a black hole that still looks alive, whereas closing lets the
    /// epidemic router re-offer the bundle over another path.
    fun sendData(bytes: ByteArray) {
        if (closed) return
        val body = byteArrayOf(FRAME_DATA.toByte()) + bytes
        val n = body.size
        if (txQueuedBytes.get() + 4 + n > MAX_TX_QUEUE_BYTES) {
            close("tx backpressure: ${txQueuedBytes.get()}B queued, peer not draining")
            return
        }
        txQueuedBytes.addAndGet((4 + n).toLong())
        txQueue.put(header(n) + body)
    }

    private fun header(n: Int) = byteArrayOf(
        (n ushr 24).toByte(),
        (n ushr 16).toByte(),
        (n ushr 8).toByte(),
        n.toByte(),
    )

    private fun sendFrame(body: ByteArray) {
        if (closed) return
        val n = body.size
        try {
            synchronized(writeLock) {
                out.write(header(n)); out.write(body); out.flush()
            }
            txBytes += (4 + n).toLong()
        } catch (e: IOException) {
            close("write: ${e.message}")
        }
    }

    /// Blocking drain of ONE queued frame, run by the link's writer thread (`l2cap-tx`). Returns
    /// false when the link is closed, so the shell's loop can exit. A null poll is a normal idle
    /// tick, not an error.
    fun writePendingOnce(timeoutMs: Long = 250): Boolean {
        if (closed) return false
        val frame = txQueue.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: return !closed
        try {
            synchronized(writeLock) {
                out.write(frame); out.flush()
            }
            txBytes += frame.size.toLong()
            txQueuedBytes.addAndGet(-frame.size.toLong())
        } catch (e: IOException) {
            close("write: ${e.message}")
            return false
        }
        return !closed
    }

    /// Test seam: bytes currently queued but not yet on the wire.
    internal val queuedBytes: Long get() = txQueuedBytes.get()

    /// Blocking read loop (Link runs this on its l2cap-rx thread): decode framed messages until closed
    /// or eof/IO error.
    fun runReadLoop() {
        val hdr = ByteArray(4)
        try {
            while (!closed) {
                readFully(hdr, 4)
                val len = (hdr[0].i shl 24) or (hdr[1].i shl 16) or (hdr[2].i shl 8) or hdr[3].i
                if (len < 1 || len > MAX_FRAME_BYTES) {
                    close("bad len $len")
                    return
                }
                val body = ByteArray(len)
                readFully(body, len)
                val now = clock()
                ewmaGapMs = 0.8 * ewmaGapMs + 0.2 * (now - lastRxMs) // R7
                lastRxMs = now
                rxBytes += (4 + len).toLong()
                handle(body)
            }
        } catch (e: IOException) {
            close("read: ${e.message}")
        }
    }

    private fun readFully(b: ByteArray, n: Int) {
        var o = 0
        while (o < n) {
            val r = inp.read(b, o, n - o)
            if (r < 0) throw IOException("eof")
            o += r
        }
    }

    private fun handle(b: ByteArray) {
        when (b[0].toInt() and 0xff) {
            FRAME_HELLO -> if (b.size >= 17 && !up) { // HELLO
                peerId = b.copyOfRange(1, 17)
                up = true
                becameUpMs = clock()
                Log.i(TAG, "LINKFLOW LINK UP link=$linkId isDialer=$isDialer peer=${peerId!!.toHex().take(8)} - HELLO both ways")
                onUp()
            }
            FRAME_PING -> { // PING → PONG. seq is the peer's monotonic keepalive counter.
                if (b.size < 9) return // mirror Apple's `guard b.count >= 9`; harden vs malformed PING
                val seq = u64dec(b, 1)
                if (rxSeq != 0L && seq != rxSeq + 1) {
                    Log.w(TAG, "counter gap $rxSeq -> $seq (peer=${peerId?.toHex()?.take(8)})")
                } else if (seq > rxSeq) {
                    Log.i(
                        TAG,
                        "RX peer counter advanced rx=$seq peer=${peerId?.toHex()?.take(8)} rxBytes=$rxBytes",
                    )
                }
                rxSeq = seq
                sendFrame(byteArrayOf(FRAME_PONG.toByte()) + b.copyOfRange(1, minOf(17, b.size)))
            }
            FRAME_PONG -> { /* PONG: reverse-direction liveness; lastRxMs already bumped in runReadLoop */ }
            FRAME_DATA -> onData(b.copyOfRange(1, b.size)) // DATA → consumer application bytes
            else -> { /* unknown frame type - ignore */ }
        }
    }

    /// Mark closed (idempotent) and notify the owner, which shuts the keepalive + closes the socket.
    fun close(why: String) {
        if (closed) return
        closed = true
        Log.i(TAG, "LINKFLOW LINK CLOSED link=$linkId ($why) isDialer=$isDialer peer=${peerId?.toHex()?.take(8)}")
        onClosed(why)
    }

    private fun u64(v: Long) = ByteArray(8) { (v ushr (56 - it * 8)).toByte() }

    private fun u64dec(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 0..7) v = (v shl 8) or (b[o + i].toLong() and 0xff)
        return v
    }

    private val Byte.i get() = toInt() and 0xff
}
