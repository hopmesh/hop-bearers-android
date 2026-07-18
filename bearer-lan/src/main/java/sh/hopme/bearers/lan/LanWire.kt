package sh.hopme.bearers.lan

// LanWire: the LAN bearer's pure, Android-free wire logic, split out so it is unit-testable on a
// plain JVM (LanBearer.kt pulls in NsdManager/Context/Socket, none of which load under a stubbed
// android.jar in a `testDebugUnitTest`). Both the byte framing AND the one-pipe-per-peer dedup
// keep-rule live here so the tests exercise the REAL production codec, not a copy.
//
// Frame grammar (byte-identical to :bearer-ble and to apple/HopBearers' LanBearer):
//   [4-byte big-endian length][1-byte type][payload]
//   HELLO 0x01 : [16B nodeId][1B role][1B flags]      role 1 = dialer
//   PING  0x02 : [8B seq][8B nowMs]
//   PONG  0x03 : echoes the peer's PING body prefix
//   DATA  0x10 : the consumer's application bytes

internal const val L_HELLO = 0x01
internal const val L_PING = 0x02
internal const val L_PONG = 0x03
internal const val L_DATA = 0x10

internal const val LAN_MAX_FRAME = 1 shl 20
internal const val LAN_MAX_PENDING_LINKS = 32
internal const val LAN_MAX_PREAUTH_BYTES_PER_LINK = LAN_MAX_FRAME
internal const val LAN_MAX_PREAUTH_BYTES_TOTAL = 4 * LAN_MAX_FRAME

/**
 * Process-wide preauthentication admission. LAN cannot observe Noise completion, so a link lease is
 * retained until link close; frame bytes remain reserved until parsing and the consumer callback finish.
 */
internal class LanAdmission(
    private val maxLinks: Int = LAN_MAX_PENDING_LINKS,
    private val maxBytesPerLink: Int = LAN_MAX_PREAUTH_BYTES_PER_LINK,
    private val maxBytesTotal: Int = LAN_MAX_PREAUTH_BYTES_TOTAL,
) {
    private val lock = Any()
    private val held = java.util.IdentityHashMap<Lease, Int>()
    private var bytes = 0

    inner class Lease internal constructor() {
        fun tryReserve(amount: Int): Boolean = synchronized(lock) {
            val current = held[this] ?: return@synchronized false
            if (amount < 0 || current + amount > maxBytesPerLink || bytes + amount > maxBytesTotal) {
                return@synchronized false
            }
            held[this] = current + amount
            bytes += amount
            true
        }

        fun release(amount: Int) = synchronized(lock) {
            val current = held[this] ?: return@synchronized
            val released = amount.coerceIn(0, current)
            held[this] = current - released
            bytes -= released
        }

        fun close() = synchronized(lock) {
            val retained = held.remove(this) ?: return@synchronized
            bytes -= retained
        }

        val retainedBytes: Int get() = synchronized(lock) { held[this] ?: 0 }
    }

    fun tryAcquire(): Lease? = synchronized(lock) {
        if (held.size >= maxLinks) return@synchronized null
        Lease().also { held[it] = 0 }
    }

    val linkCount: Int get() = synchronized(lock) { held.size }
    val retainedBytes: Int get() = synchronized(lock) { bytes }
}

internal val LAN_ADMISSION = LanAdmission()

/// One decoded frame. `payload` excludes the 1-byte type tag.
internal data class LanFrame(val type: Int, val payload: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is LanFrame && other.type == type && other.payload.contentEquals(payload)
    override fun hashCode(): Int = 31 * type + payload.contentHashCode()
}

/// Pure LAN frame codec: the exact bytes that go on / come off the TCP socket. `encode` produces the
/// full on-wire frame (4-byte length prefix + type + payload); `decodeBody` parses a body (type +
/// payload) already stripped of its length prefix (as LanLink.handle receives it).
internal object LanWire {
    /// The 4-byte big-endian length prefix for a body of [n] bytes.
    fun lengthPrefix(n: Int): ByteArray = byteArrayOf(
        (n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte(),
    )

    /// Read the big-endian length prefix back to an Int (mirrors LanLink.readLoop).
    fun readLength(hdr: ByteArray): Int {
        require(hdr.size >= 4)
        fun i(b: Byte) = b.toInt() and 0xff
        return (i(hdr[0]) shl 24) or (i(hdr[1]) shl 16) or (i(hdr[2]) shl 8) or i(hdr[3])
    }

    /// A body is valid iff its declared length is in [1, LAN_MAX_FRAME] (LanLink rejects otherwise).
    fun isValidLength(len: Int): Boolean = len in 1..LAN_MAX_FRAME

    /// The full on-wire bytes for a body: [length prefix][body].
    fun encodeFrame(body: ByteArray): ByteArray = lengthPrefix(body.size) + body

    fun hello(myId: ByteArray, isDialer: Boolean): ByteArray =
        byteArrayOf(L_HELLO.toByte()) + myId + byteArrayOf((if (isDialer) 1 else 0).toByte(), 0)

    fun ping(seq: Long, nowMs: Long): ByteArray =
        byteArrayOf(L_PING.toByte()) + u64(seq) + u64(nowMs)

    fun data(bytes: ByteArray): ByteArray = byteArrayOf(L_DATA.toByte()) + bytes

    /// Decode a body (type + payload, no length prefix) into a LanFrame. Returns null for an empty body.
    fun decodeBody(body: ByteArray): LanFrame? {
        if (body.isEmpty()) return null
        return LanFrame(body[0].toInt() and 0xff, body.copyOfRange(1, body.size))
    }

    /// The 16-byte peerId carried by a HELLO body, or null if the body is too short.
    fun helloPeerId(body: ByteArray): ByteArray? =
        if (body.size >= 17) body.copyOfRange(1, 17) else null

    /// Whether a HELLO body marks the sender as the dialer (role byte == 1).
    fun helloIsDialer(body: ByteArray): Boolean = body.size >= 18 && body[17].toInt() == 1

    fun u64(v: Long): ByteArray = ByteArray(8) { (v ushr (56 - it * 8)).toByte() }

    fun u64dec(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 0..7) v = (v shl 8) or (b[o + i].toLong() and 0xff)
        return v
    }
}

/// The pure one-pipe-per-peer dedup keep-rule. When two links to the SAME peer are both up (each side
/// dialed the other because discovery is symmetric), exactly one survives: keep the channel whose
/// `isDialer` matches "am I the greater nodeId?". So the greater-id side keeps ITS dialed channel and
/// the lesser-id side keeps the channel it accepted, so both ends independently agree on the same
/// survivor. Returns which of `existing`/`incoming` to KEEP.
internal enum class DedupKeep { EXISTING, INCOMING }

internal object LanDedup {
    /// [amGreater] = nodeIdGreater(myId, peerId). [existingIsDialer]/[incomingIsDialer] = the role of
    /// each competing link from MY perspective. Returns which link to keep.
    fun decide(amGreater: Boolean, existingIsDialer: Boolean, incomingIsDialer: Boolean): DedupKeep {
        // Keep MY dialed channel iff I'm the greater id; else keep MY accepted channel.
        val keepDialed = amGreater
        // Prefer whichever competing link's role matches; if neither matches (shouldn't happen for a
        // real dialer/acceptor pair) fall back to the incoming one, matching LanBearer.onUp.
        return when {
            existingIsDialer == keepDialed && incomingIsDialer != keepDialed -> DedupKeep.EXISTING
            incomingIsDialer == keepDialed && existingIsDialer != keepDialed -> DedupKeep.INCOMING
            existingIsDialer == keepDialed -> DedupKeep.EXISTING // both match (degenerate): keep existing
            else -> DedupKeep.INCOMING
        }
    }
}
