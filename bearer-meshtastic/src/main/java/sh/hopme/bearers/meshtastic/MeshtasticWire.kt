package sh.hopme.bearers.meshtastic

// MeshtasticWire: the Meshtastic bearer's PURE, Android-free wire logic, split out so it is unit-testable
// on a plain JVM (MeshtasticBearer.kt pulls in android.bluetooth.*, which does not load under a stubbed
// android.jar). This is the byte-for-byte twin of bearers/apple/HopBearerMeshtastic's MeshtasticWire.swift:
// the minimal Meshtastic protobuf codec, the fragment/reassembly layer that carries a Hop link frame across
// tiny LoRa packets, the Hop link-frame grammar (identical tags to the LAN/BLE bearers), and the dedup
// keep-rule. Apple <-> Android interop rides on these staying identical, which tools/meshtastic-parity.sh
// enforces against bearers/meshtastic-vectors.json.
//
// Hop link-frame grammar (same 1-byte tags as :bearer-lan):
//   HELLO 0x01 : [16B nodeId][1B role][1B flags]   role 1 = the greater-id side (Noise initiator)
//   PING  0x02 : [8B seq][8B nowMs]
//   PONG  0x03 : echoes the peer's PING body prefix
//   DATA  0x10 : the consumer's application bytes
//
// Fragment header prepended to each Meshtastic payload (4 bytes):
//   [msgId hi][msgId lo][fragIndex][fragCount]  then up to MESH_MAX_CHUNK bytes of the frame body

// ---- Pinned cross-platform constants (see bearers/meshtastic-vectors.json) ----------------------------

/** Meshtastic PortNum for Hop traffic (inside the PRIVATE_APP 256..511 range). */
internal const val MESH_HOP_PORTNUM = 260

/** The Meshtastic broadcast node address. */
internal const val MESH_BROADCAST_ADDR = 0xFFFFFFFFL

/** Max Hop bytes per Meshtastic packet (a Data.payload tops out near 237; 200 leaves header headroom). */
internal const val MESH_MAX_CHUNK = 200

/** Fragment header size: [msgId:2][fragIndex:1][fragCount:1]. */
internal const val MESH_FRAG_HEADER = 4

/** A frame is split across at most 255 fragments (fragCount is one byte). */
internal const val MESH_MAX_FRAGS = 255
internal const val MESH_MAX_MESSAGE = MESH_MAX_FRAGS * MESH_MAX_CHUNK

/** Liveness (LoRa is slow + duty-cycle limited): PING every 30 s, dead after 180 s of silence. */
internal const val MESH_PING_MS = 30_000L
internal const val MESH_DEAD_MS = 180_000L

/** Drop a half-assembled inbound message after this long; bound concurrent partials per peer. */
internal const val MESH_REASSEMBLY_TTL_MS = 120_000L
internal const val MESH_MAX_PARTIAL_PER_PEER = 8

// Hop link-frame type tags (identical to :bearer-lan L_HELLO/L_PING/L_PONG/L_DATA).
internal const val M_HELLO = 0x01
internal const val M_PING = 0x02
internal const val M_PONG = 0x03
internal const val M_DATA = 0x10

// ---- Minimal protobuf codec (only the Meshtastic messages the bearer needs) ---------------------------

/** A tiny protobuf writer: varint, fixed32, and length-delimited fields. Hand-encoding the exact subset
 *  the bearer needs is far lighter than pulling the generated Meshtastic SDK, and is fully unit-testable. */
internal class ProtoWriter {
    private val out = ArrayList<Byte>()

    fun varintField(field: Int, value: Long): ProtoWriter { tag(field, 0); varint(value); return this }

    fun fixed32Field(field: Int, value: Long): ProtoWriter {
        tag(field, 5)
        for (i in 0 until 4) out.add(((value ushr (8 * i)) and 0xff).toByte())   // little-endian
        return this
    }

    fun bytesField(field: Int, value: ByteArray): ProtoWriter {
        tag(field, 2); varint(value.size.toLong()); for (b in value) out.add(b); return this
    }

    fun toBytes(): ByteArray = out.toByteArray()

    private fun tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())

    private fun varint(v: Long) {
        var value = v
        do {
            var b = (value and 0x7f).toInt()
            value = value ushr 7
            if (value != 0L) b = b or 0x80
            out.add(b.toByte())
        } while (value != 0L)
    }
}

/** A tiny bounds-checked protobuf reader. Every read returns null on a malformed/truncated buffer, so a
 *  hostile radio frame can never index out of range. */
internal class ProtoReader(private val buf: ByteArray) {
    private var i = 0

    val atEnd: Boolean get() = i >= buf.size

    /** (fieldNumber, wireType) or null at end / on a truncated tag. */
    fun readTag(): Pair<Int, Int>? {
        val t = readVarint() ?: return null
        return Pair((t ushr 3).toInt(), (t and 0x7).toInt())
    }

    fun readVarint(): Long? {
        var result = 0L
        var shift = 0
        while (i < buf.size) {
            val b = buf[i].toInt() and 0xff; i++
            if (shift > 63) return null
            result = result or ((b.toLong() and 0x7f) shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
        return null
    }

    fun readFixed32(): Long? {
        if (i + 4 > buf.size) return null
        var v = 0L
        for (k in 0 until 4) v = v or ((buf[i + k].toLong() and 0xff) shl (8 * k))
        i += 4
        return v
    }

    fun readBytes(): ByteArray? {
        val len = readVarint()?.toInt() ?: return null
        if (len < 0 || i + len > buf.size) return null
        val out = buf.copyOfRange(i, i + len)
        i += len
        return out
    }

    /** Skip a field of the given wire type; false on a malformed buffer. */
    fun skip(wire: Int): Boolean = when (wire) {
        0 -> readVarint() != null
        1 -> if (i + 8 <= buf.size) { i += 8; true } else false
        2 -> readBytes() != null
        5 -> readFixed32() != null
        else -> false
    }
}

// ---- Meshtastic messages (the exact subset the bearer speaks) -----------------------------------------

/** What the bearer acts on after decoding one FromRadio: our own node number, or a Hop-port data packet. */
internal sealed class MeshInbound {
    data class MyNodeNum(val num: Long) : MeshInbound()
    data class HopData(val from: Long, val payload: ByteArray) : MeshInbound() {
        override fun equals(other: Any?): Boolean =
            other is HopData && other.from == from && other.payload.contentEquals(payload)
        override fun hashCode(): Int = 31 * from.hashCode() + payload.contentHashCode()
    }
}

internal object MeshtasticProto {
    // Field numbers from the stable Meshtastic mesh.proto (wire types matter):
    //   Data:       portnum=1 (varint), payload=2 (bytes)
    //   MeshPacket: from=1 (fixed32), to=2 (fixed32), decoded=4 (Data), id=6 (fixed32), hop_limit=9 (varint)
    //   ToRadio:    packet=1 (MeshPacket), want_config_id=3 (varint)
    //   FromRadio:  packet=2 (MeshPacket), my_info=3 (MyNodeInfo)
    //   MyNodeInfo: my_node_num=1 (varint)

    fun encodeData(payload: ByteArray): ByteArray =
        ProtoWriter().varintField(1, MESH_HOP_PORTNUM.toLong()).bytesField(2, payload).toBytes()

    fun encodeToRadioPacket(from: Long, to: Long, id: Long, hopLimit: Int, fragment: ByteArray): ByteArray {
        val pkt = ProtoWriter()
            .fixed32Field(1, from)
            .fixed32Field(2, to)
            .bytesField(4, encodeData(fragment))
            .fixed32Field(6, id)
            .varintField(9, hopLimit.toLong())
            .toBytes()
        return ProtoWriter().bytesField(1, pkt).toBytes()
    }

    fun encodeWantConfig(nonce: Long): ByteArray = ProtoWriter().varintField(3, nonce).toBytes()

    fun decodeFromRadio(bytes: ByteArray): MeshInbound? {
        val r = ProtoReader(bytes)
        while (true) {
            val (field, wire) = r.readTag() ?: return null
            when {
                field == 2 && wire == 2 -> {
                    val sub = r.readBytes() ?: return null
                    decodeMeshPacket(sub)?.let { return it }
                }
                field == 3 && wire == 2 -> {
                    val sub = r.readBytes() ?: return null
                    decodeMyNodeNum(sub)?.let { return MeshInbound.MyNodeNum(it) }
                }
                else -> if (!r.skip(wire)) return null
            }
        }
    }

    fun decodeMeshPacket(bytes: ByteArray): MeshInbound? {
        val r = ProtoReader(bytes)
        var from = 0L
        var decoded: ByteArray? = null
        while (true) {
            val (field, wire) = r.readTag() ?: break
            when {
                field == 1 && wire == 5 -> from = r.readFixed32() ?: return null
                field == 4 && wire == 2 -> decoded = r.readBytes() ?: return null
                else -> if (!r.skip(wire)) return null
            }
        }
        val data = decoded ?: return null
        val payload = decodeHopData(data) ?: return null
        return MeshInbound.HopData(from, payload)
    }

    /** The payload of a Data submessage IFF it is on the Hop port, else null. */
    fun decodeHopData(bytes: ByteArray): ByteArray? {
        val r = ProtoReader(bytes)
        var portnum = -1L
        var payload: ByteArray? = null
        while (true) {
            val (field, wire) = r.readTag() ?: break
            when {
                field == 1 && wire == 0 -> portnum = r.readVarint() ?: return null
                field == 2 && wire == 2 -> payload = r.readBytes() ?: return null
                else -> if (!r.skip(wire)) return null
            }
        }
        if (portnum != MESH_HOP_PORTNUM.toLong()) return null
        return payload ?: ByteArray(0)
    }

    fun decodeMyNodeNum(bytes: ByteArray): Long? {
        val r = ProtoReader(bytes)
        while (true) {
            val (field, wire) = r.readTag() ?: return null
            if (field == 1 && wire == 0) return r.readVarint()
            if (!r.skip(wire)) return null
        }
    }
}

// ---- Hop link-frame grammar (identical tags to :bearer-lan) --------------------------------------------

internal object MeshFrame {
    fun hello(myId: ByteArray, isGreater: Boolean): ByteArray =
        byteArrayOf(M_HELLO.toByte()) + myId + byteArrayOf((if (isGreater) 1 else 0).toByte(), 0)

    fun ping(seq: Long, nowMs: Long): ByteArray =
        byteArrayOf(M_PING.toByte()) + u64(seq) + u64(nowMs)

    fun pong(echo: ByteArray): ByteArray = byteArrayOf(M_PONG.toByte()) + echo

    fun data(payload: ByteArray): ByteArray = byteArrayOf(M_DATA.toByte()) + payload

    /** The 16-byte peerId a HELLO body carries, or null if too short. */
    fun helloPeerId(body: ByteArray): ByteArray? = if (body.size >= 17) body.copyOfRange(1, 17) else null

    fun u64(v: Long): ByteArray = ByteArray(8) { (v ushr (56 - it * 8)).toByte() }

    fun u64dec(b: ByteArray, o: Int): Long {
        var v = 0L
        for (k in 0..7) if (o + k < b.size) v = (v shl 8) or (b[o + k].toLong() and 0xff)
        return v
    }
}

// ---- Fragmentation + reassembly ------------------------------------------------------------------------

/** Split a Hop link-frame body into fragments that each fit one Meshtastic packet, prefixed with
 *  [msgId:2][fragIndex:1][fragCount:1]. An empty body yields ONE empty fragment. Null if the body exceeds
 *  MESH_MAX_MESSAGE. */
internal fun meshFragment(body: ByteArray, msgId: Int): List<ByteArray>? {
    if (body.size > MESH_MAX_MESSAGE) return null
    val count = if (body.isEmpty()) 1 else (body.size + MESH_MAX_CHUNK - 1) / MESH_MAX_CHUNK
    if (count > MESH_MAX_FRAGS) return null
    val out = ArrayList<ByteArray>(count)
    for (idx in 0 until count) {
        val start = idx * MESH_MAX_CHUNK
        val end = minOf(start + MESH_MAX_CHUNK, body.size)
        val header = byteArrayOf(
            ((msgId ushr 8) and 0xff).toByte(), (msgId and 0xff).toByte(), idx.toByte(), count.toByte(),
        )
        out.add(if (start < end) header + body.copyOfRange(start, end) else header)
    }
    return out
}

/** The parsed header of one inbound fragment, or null for a runt / inconsistent header. */
internal class MeshFragHeader private constructor(
    val msgId: Int,
    val index: Int,
    val count: Int,
    val chunk: ByteArray,
) {
    companion object {
        fun parse(frag: ByteArray): MeshFragHeader? {
            if (frag.size < MESH_FRAG_HEADER) return null
            val id = ((frag[0].toInt() and 0xff) shl 8) or (frag[1].toInt() and 0xff)
            val idx = frag[2].toInt() and 0xff
            val cnt = frag[3].toInt() and 0xff
            if (cnt < 1 || cnt > MESH_MAX_FRAGS || idx >= cnt) return null
            return MeshFragHeader(id, idx, cnt, frag.copyOfRange(MESH_FRAG_HEADER, frag.size))
        }
    }
}

/** Per-peer reassembly of fragmented Hop frames, keyed by (peer node num, msgId). Pure: the caller supplies
 *  `nowMs` so it is deterministically testable with no clock. Not thread-safe; the bearer owns it on its
 *  single work thread (mirroring the Apple bearer's serial queue). */
internal class MeshReassembler {
    private class Partial(val count: Int, val firstSeenMs: Long) {
        val chunks = HashMap<Int, ByteArray>()
    }

    // node num -> msgId -> Partial
    private val partials = HashMap<Long, HashMap<Int, Partial>>()

    fun accept(peer: Long, fragment: ByteArray, nowMs: Long): ByteArray? {
        val h = MeshFragHeader.parse(fragment) ?: return null
        evictStale(nowMs)
        val byId = partials.getOrPut(peer) { HashMap() }

        if (h.count == 1) {
            if (byId.isEmpty()) partials.remove(peer)
            return h.chunk
        }

        var p = byId[h.msgId]
        if (p == null || p.count != h.count) { p = Partial(h.count, nowMs); byId[h.msgId] = p }
        p.chunks[h.index] = h.chunk

        if (byId.size > MESH_MAX_PARTIAL_PER_PEER) {
            byId.minByOrNull { it.value.firstSeenMs }?.key?.let { byId.remove(it) }
        }

        if (p.chunks.size != p.count) return null

        val body = ArrayList<Byte>()
        for (idx in 0 until p.count) p.chunks[idx]?.forEach { body.add(it) }
        byId.remove(h.msgId)
        if (byId.isEmpty()) partials.remove(peer)
        return body.toByteArray()
    }

    fun evictStale(nowMs: Long) {
        val deadPeers = ArrayList<Long>()
        for ((peer, byId) in partials) {
            byId.entries.removeAll { nowMs - it.value.firstSeenMs > MESH_REASSEMBLY_TTL_MS }
            if (byId.isEmpty()) deadPeers.add(peer)
        }
        deadPeers.forEach { partials.remove(it) }
    }

    fun forget(peer: Long) { partials.remove(peer) }

    val partialPeerCount: Int get() = partials.size
}

// ---- Dedup keep-rule (shared with every other bearer) -------------------------------------------------

/** Keep the leg whose "I am the greater id" role matches, identical to the LAN/BLE bearers. */
internal fun meshKeepGreaterLeg(amGreater: Boolean): Boolean = amGreater
