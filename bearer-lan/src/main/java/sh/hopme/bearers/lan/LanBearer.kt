package sh.hopme.bearers.lan

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import sh.hop.Bearer
import sh.hop.LinkId
import sh.hop.HopRole
import sh.hop.LinkSink
import sh.hop.TAG
import sh.hop.nodeIdGreater
import sh.hop.toHex
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

// LanBearer — the LAN transport as its OWN library (depends only on :bearer-core). Two devices on the
// same Wi-Fi/LAN discover each other over NSD/Bonjour (`_hoplan._tcp`) and talk over TCP. It is fully
// self-contained: nothing here is shared with :bearer-ble (per "each bearer its own lib") — but it
// speaks the SAME link grammar as the BLE bearer AND the Apple LanBearer, so the consumer sees identical
// linkUp/linkBytes/linkDown semantics regardless of radio, and Android<->Apple interop on the wire:
//
//   - 4-byte big-endian length prefix + a 1-byte frame type: HELLO 0x01, PING 0x02, PONG 0x03, DATA 0x10.
//   - HELLO carries the 16-byte nodeId so both ends learn the peer id (dedup + the consumer's key).
//   - 1 Hz PING keepalive feeds a liveness watchdog (~15 s dead) + a no-HELLO reaper (~5 s); PING/PONG
//     never surface to the consumer.
//   - DATA (0x10) is the consumer seam: Bearer.send wraps bytes in a DATA frame; inbound DATA ->
//     sink.linkBytes. One-pipe-per-peer dedup with the "greater nodeId dials" tiebreaker.
//
// Discovery: the NSD instance name IS our nodeId as 32 hex chars, so a browser learns the peer id
// WITHOUT connecting (mirrors how the BLE advert carries the id prefix and how Apple sets the Bonjour
// instance name). The greater nodeId dials; the lesser only listens — so a mutually-discovered pair
// forms exactly one connection per direction, then dedup keeps one.
//
// THREADING (the Android difference from Apple's single serial queue): callbacks arrive from MULTIPLE
// threads — NsdManager's internal threads (discovery/resolve/register), the accept thread, the per-link
// rx threads, and the per-link keepalive ScheduledExecutors. Every link/dedup-map mutation/read is
// guarded by `lock` (the same discipline BleBearer/BearerManager use).

internal const val LAN_SERVICE_TYPE = "_hoplan._tcp"

private const val LAN_PING_MS = 1000L     // 1 Hz keepalive
private const val LAN_DEAD_MS = 15_000L   // TCP is reliable; a generous liveness deadline
private const val LAN_REAP_MS = 5_000L    // close a connection that never completes HELLO
private const val LAN_MAX_FRAME = 4 * 1024 * 1024
private const val LAN_DIAL_TIMEOUT_MS = 5_000
private const val LAN_RETRY_S = 5L   // F-14: rescan cadence to re-dial peers lost to a transient failure

// Wire frame types — byte-identical to apple/HopBearers' LanBearer and to :bearer-ble's framing.
private const val L_HELLO = 0x01
private const val L_PING = 0x02
private const val L_PONG = 0x03
private const val L_DATA = 0x10

// ---- LanLink: one TCP socket, same framing/keepalive/HELLO grammar as the BLE link + Apple LanLink ----
internal class LanLink(
    private val socket: Socket,
    val linkId: Long,
    val isDialer: Boolean,
    private val myId: ByteArray,
    private val onUp: (LanLink) -> Unit,
    private val onData: (LanLink, ByteArray) -> Unit,
    private val onClose: (LanLink) -> Unit,
) {
    @Volatile
    var peerId: ByteArray? = null

    @Volatile
    var up = false

    @Volatile
    private var lastRxMs = System.currentTimeMillis()
    private val openedMs = System.currentTimeMillis()
    private var txSeq = 0L
    private var rxSeq = 0L

    @Volatile
    private var closed = false
    private val out: OutputStream = socket.getOutputStream()
    private val inp: InputStream = socket.getInputStream()
    private val writeLock = Any()
    private val sched: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    fun start() {
        runCatching { socket.tcpNoDelay = true }
        // HELLO first (same grammar as BLE/Apple): [0x01][16B nodeId][1B role][1B flags]
        sendFrame(byteArrayOf(L_HELLO.toByte()) + myId + byteArrayOf((if (isDialer) 1 else 0).toByte(), 0))
        Log.i(TAG, "lan channel-ready isDialer=$isDialer — sent HELLO")
        thread(name = "lan-rx") { readLoop() }
        sched.scheduleAtFixedRate({ tick() }, LAN_PING_MS, LAN_PING_MS, TimeUnit.MILLISECONDS)
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        if (!up && now - openedMs > LAN_REAP_MS) { close("no-HELLO reap"); return }
        if (up && now - lastRxMs > LAN_DEAD_MS) { close("liveness DEAD (silent ${now - lastRxMs}ms)"); return }
        txSeq++
        sendFrame(byteArrayOf(L_PING.toByte()) + u64(txSeq) + u64(now))
    }

    /// Bearer.send entry point: wrap the consumer's application bytes in a DATA frame (0x10) and send.
    fun sendData(bytes: ByteArray) {
        if (closed) return
        sendFrame(byteArrayOf(L_DATA.toByte()) + bytes)
    }

    private fun sendFrame(body: ByteArray) {
        if (closed) return
        val n = body.size
        val hdr = byteArrayOf(
            (n ushr 24).toByte(),
            (n ushr 16).toByte(),
            (n ushr 8).toByte(),
            n.toByte(),
        )
        try {
            synchronized(writeLock) { out.write(hdr); out.write(body); out.flush() }
        } catch (e: IOException) {
            close("write: ${e.message}")
        }
    }

    private fun readLoop() {
        val hdr = ByteArray(4)
        try {
            while (!closed) {
                readFully(hdr, 4)
                val len = (hdr[0].i shl 24) or (hdr[1].i shl 16) or (hdr[2].i shl 8) or hdr[3].i
                if (len < 1 || len > LAN_MAX_FRAME) { close("bad len $len"); return }
                val body = ByteArray(len)
                readFully(body, len)
                lastRxMs = System.currentTimeMillis()
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
            L_HELLO -> if (b.size >= 17 && !up) { // HELLO learns the peer id → surface linkUp
                peerId = b.copyOfRange(1, 17)
                up = true
                Log.i(TAG, "lan hello-recv peer=${peerId!!.toHex().take(8)} isDialer=$isDialer")
                onUp(this)
            }
            L_PING -> { // PING → PONG. seq is the peer's monotonic keepalive counter (never surfaced).
                if (b.size < 9) return
                rxSeq = u64dec(b, 1)
                sendFrame(byteArrayOf(L_PONG.toByte()) + b.copyOfRange(1, minOf(17, b.size)))
            }
            L_PONG -> { /* reverse-direction liveness; lastRxMs already bumped in readLoop */ }
            L_DATA -> onData(this, b.copyOfRange(1, b.size)) // DATA → consumer application bytes
            else -> { /* unknown frame type — ignore */ }
        }
    }

    fun close(why: String) {
        if (closed) return
        closed = true
        Log.i(TAG, "lan link-down ($why) peer=${peerId?.toHex()?.take(8)} isDialer=$isDialer")
        sched.shutdownNow()
        try { socket.close() } catch (_: IOException) {}
        onClose(this)
    }

    private fun u64(v: Long) = ByteArray(8) { (v ushr (56 - it * 8)).toByte() }
    private fun u64dec(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 0..7) v = (v shl 8) or (b[o + i].toLong() and 0xff)
        return v
    }
    private val Byte.i get() = toInt() and 0xff
}

// ---- LanBearer: NSD register + discover, one TCP ServerSocket, one-pipe-per-peer dedup, monotonic id --
class LanBearer(private val ctx: Context, private val myId: ByteArray) : Bearer {
    override var sink: LinkSink? = null
    /// Short transport tag for the consumer's UI (Bearer contract). LAN (mDNS+TCP) links surface as "LAN".
    override val transportName = "LAN"

    private val lock = Any()
    private val linksByPeerId = HashMap<String, LanLink>()  // dedup: one survivor per peer
    private val linksByLinkId = HashMap<Long, LanLink>()     // send routing + linkUp/linkDown pairing
    private val dialing = HashSet<String>()                  // peerId-hex currently being dialed (pre-HELLO dedup)
    private var nextLinkId = 1L

    private var server: ServerSocket? = null
    private var nsd: NsdManager? = null
    private var regListener: NsdManager.RegistrationListener? = null
    private var discListener: NsdManager.DiscoveryListener? = null

    // resolveService is one-at-a-time on the platform; serialize with a queue so no discovered peer is
    // dropped while another resolve is in flight (all guarded by `lock`).
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    // F-14: discovery is one-shot, so a transient resolve/dial failure used to silently forfeit a peer
    // until mDNS re-announced (which may not happen for a long time). Remember the last-seen service per
    // peer and periodically re-attempt any known-but-unlinked one — the BLE central re-dials on every
    // advert; LAN gets the same pressure here. With Wi-Fi Direct gone, LAN is the only Wi-Fi transport.
    private val knownServices = HashMap<String, NsdServiceInfo>()
    private val retryExec: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    @Volatile
    private var stopped = false

    @Volatile
    private var port = 0

    private val myHex = myId.toHex()

    override fun start() {
        stopped = false
        Log.i(TAG, "lan node-start myId=$myHex service=$LAN_SERVICE_TYPE")
        thread(name = "lan-start") { startListener(); startNsd() }
        retryExec.scheduleAtFixedRate({ runCatching { rescan() } }, LAN_RETRY_S, LAN_RETRY_S, TimeUnit.SECONDS)
    }

    override fun stop() {
        stopped = true
        retryExec.shutdownNow()
        val n = nsd
        regListener?.let { runCatching { n?.unregisterService(it) } }; regListener = null
        discListener?.let { runCatching { n?.stopServiceDiscovery(it) } }; discListener = null
        nsd = null
        runCatching { server?.close() }; server = null
        synchronized(lock) { knownServices.clear() }
        val all = synchronized(lock) { linksByPeerId.values.toList() }
        all.forEach { it.close("stop") }
    }

    // F-14: re-dial any peer we've seen but aren't linked to / already dialing. Covers a peer lost to a
    // transient resolve or connect failure (both of which just cleared `dialing` with no retry).
    private fun rescan() {
        if (stopped) return
        val toResolve = ArrayList<NsdServiceInfo>()
        synchronized(lock) {
            for ((peerHex, svc) in knownServices) {
                if (linksByPeerId.containsKey(peerHex) || dialing.contains(peerHex)) continue
                val pid = peerIdFromName(svc.serviceName) ?: continue
                if (!nodeIdGreater(myId, pid)) continue     // tiebreaker: only the greater id dials
                dialing.add(peerHex)
                toResolve.add(svc)
            }
        }
        for (svc in toResolve) {
            Log.i(TAG, "lan rescan re-resolve ${svc.serviceName?.take(8)}")
            enqueueResolve(svc)
        }
    }

    override fun send(bytes: ByteArray, link: LinkId) {
        val l = synchronized(lock) { linksByLinkId[link] } // no-op if link closed/unknown
        l?.sendData(bytes)
    }

    private fun mint(): Long = synchronized(lock) { val id = nextLinkId; nextLinkId += 1; id }

    // One TCP server accepts every inbound link; the OS picks an ephemeral port that NSD then advertises.
    private fun startListener() {
        try {
            val s = ServerSocket()
            s.reuseAddress = true
            s.bind(InetSocketAddress(0)) // ephemeral port
            server = s
            port = s.localPort
            Log.i(TAG, "lan listening port=$port name=${myHex.take(8)}")
            thread(name = "lan-accept") {
                while (true) {
                    val sock = try {
                        s.accept()
                    } catch (e: IOException) {
                        Log.i(TAG, "lan accept loop ended: ${e.message}")
                        break
                    }
                    Log.i(TAG, "lan inbound-connection (acceptor)")
                    LanLink(sock, mint(), isDialer = false, myId, ::onUp, ::onData, ::onClose).start()
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "lan listener bind failed: ${e.message}")
        }
    }

    private fun startNsd() {
        val n = ctx.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: run {
            Log.w(TAG, "lan NsdManager unavailable"); return
        }
        nsd = n
        // The NSD instance name IS our nodeId (32 hex), so a browser learns the peer id without connecting.
        val info = NsdServiceInfo().apply {
            serviceName = myHex
            serviceType = LAN_SERVICE_TYPE
            port = this@LanBearer.port
        }
        val reg = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(s: NsdServiceInfo) { Log.i(TAG, "lan nsd registered ${s.serviceName?.take(8)}") }
            override fun onRegistrationFailed(s: NsdServiceInfo, e: Int) { Log.w(TAG, "lan nsd register failed: $e") }
            override fun onServiceUnregistered(s: NsdServiceInfo) {}
            override fun onUnregistrationFailed(s: NsdServiceInfo, e: Int) {}
        }
        regListener = reg
        runCatching { n.registerService(info, NsdManager.PROTOCOL_DNS_SD, reg) }

        val disc = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) { Log.w(TAG, "lan nsd discover failed: $e") }
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onServiceFound(s: NsdServiceInfo) { handleServiceFound(s.serviceName, s) }
            override fun onServiceLost(s: NsdServiceInfo) {
                peerIdFromName(s.serviceName)?.let { synchronized(lock) { knownServices.remove(it.toHex()) } }
            }
        }
        discListener = disc
        runCatching { n.discoverServices(LAN_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, disc) }
    }

    // A peer service appeared. Parse the name back to a 16-byte peerId and apply the dial gates, exactly
    // like Apple's browseResultsChangedHandler: skip self, skip if already linked, greater nodeId dials,
    // dedup in-flight dials. Then enqueue a resolve (host/port) → dial.
    private fun handleServiceFound(name: String?, svc: NsdServiceInfo) {
        val peerId = name?.let { peerIdFromName(it) } ?: return
        val peerHex = peerId.toHex()
        if (peerHex == myHex) return                                       // our own advertised service
        synchronized(lock) { knownServices[peerHex] = svc }               // F-14: remember for rescan/retry
        if (!nodeIdGreater(myId, peerId)) return                           // tiebreaker: greater dials
        synchronized(lock) {
            if (linksByPeerId.containsKey(peerHex)) return                 // already linked
            if (!dialing.add(peerHex)) return                              // already dialing this peer
        }
        Log.i(TAG, "lan discovered peer=${peerHex.take(8)} -> DIAL")
        enqueueResolve(svc)
    }

    private fun enqueueResolve(svc: NsdServiceInfo) {
        synchronized(lock) { resolveQueue.addLast(svc) }
        pumpResolve()
    }

    private fun pumpResolve() {
        val svc = synchronized(lock) {
            if (resolving) return
            val s = resolveQueue.removeFirstOrNull() ?: return
            resolving = true
            s
        }
        val n = nsd ?: run { synchronized(lock) { resolving = false }; return }
        @Suppress("DEPRECATION") // resolveService is deprecated in API 34 but is the minSdk-29 path
        n.resolveService(svc, object : NsdManager.ResolveListener {
            override fun onResolveFailed(s: NsdServiceInfo, e: Int) {
                peerIdFromName(s.serviceName)?.let { pid -> synchronized(lock) { dialing.remove(pid.toHex()) } }
                synchronized(lock) { resolving = false }
                pumpResolve()
            }
            override fun onServiceResolved(s: NsdServiceInfo) {
                synchronized(lock) { resolving = false }
                dial(s)
                pumpResolve()
            }
        })
    }

    @Suppress("DEPRECATION") // NsdServiceInfo.host is deprecated in API 34 but is the minSdk-29 path
    private fun dial(svc: NsdServiceInfo) {
        val peerId = peerIdFromName(svc.serviceName) ?: return
        val peerHex = peerId.toHex()
        val host = svc.host ?: run { synchronized(lock) { dialing.remove(peerHex) }; return }
        val p = svc.port
        thread(name = "lan-dial") {
            val sock = try {
                Socket().apply { connect(InetSocketAddress(host, p), LAN_DIAL_TIMEOUT_MS) }
            } catch (e: IOException) {
                Log.w(TAG, "lan dial failed peer=${peerHex.take(8)}: ${e.message}")
                synchronized(lock) { dialing.remove(peerHex) }
                return@thread
            }
            Log.i(TAG, "lan dialed peer=${peerHex.take(8)} — wrapping link")
            LanLink(
                sock, mint(), isDialer = true, myId, ::onUp, ::onData,
                onClose = { l -> synchronized(lock) { dialing.remove(peerHex) }; onClose(l) },
            ).start()
        }
    }

    // ---- link lifecycle (callbacks arrive on rx / accept / dial threads; maps guarded by lock) --------

    private fun onUp(link: LanLink) { // HELLO completed: surface to sink, then dedup
        val peer = link.peerId ?: return
        val key = peer.toHex()
        synchronized(lock) { linksByLinkId[link.linkId] = link } // register for send routing + down pairing
        // Surface BEFORE dedup (Apple parity): both legs of a duplicate pair come up, then dedup closes
        // the loser → the consumer sees that loser's linkDown.
        sink?.linkUp(link.linkId, if (link.isDialer) HopRole.DIALER else HopRole.ACCEPTOR, peer)
        var drop: LanLink? = null
        synchronized(lock) {
            val existing = linksByPeerId[key]
            if (existing == null || existing === link) {
                linksByPeerId[key] = link
            } else {
                val keepDialed = nodeIdGreater(myId, peer) // keep MY dialed channel iff I'm the greater id
                val keep = listOf(existing, link).firstOrNull { it.isDialer == keepDialed } ?: link
                drop = if (keep === link) existing else link
                linksByPeerId[key] = keep // set survivor BEFORE closing the dropped channel
                Log.i(TAG, "lan DEDUP kept isDialer=${keep.isDialer} peer=${key.take(8)}")
            }
        }
        drop?.close("dedup") // outside lock: close → onClose → sink.linkDown for the loser
    }

    private fun onData(link: LanLink, bytes: ByteArray) {
        sink?.linkBytes(link.linkId, bytes) // one DATA frame → consumer
    }

    private fun onClose(link: LanLink) { // identity-checked removal
        val peer = link.peerId
        val wasUp: Boolean
        synchronized(lock) {
            wasUp = linksByLinkId.remove(link.linkId) != null // true iff linkUp had fired
            if (peer != null) {
                val key = peer.toHex()
                if (linksByPeerId[key] === link) linksByPeerId.remove(key)
            }
        }
        if (wasUp) sink?.linkDown(link.linkId) // pair every linkDown with a prior linkUp
    }
}

/// The NSD instance name is the peer's 32-hex-char nodeId. Parse it back to 16 bytes (mirror Apple's
/// peerIdFromName). Returns null for any name that is not exactly 32 hex chars.
private fun peerIdFromName(name: String?): ByteArray? {
    if (name == null || name.length != 32) return null
    val out = ByteArray(16)
    for (i in 0 until 16) {
        val hi = Character.digit(name[i * 2], 16)
        val lo = Character.digit(name[i * 2 + 1], 16)
        if (hi < 0 || lo < 0) return null
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}
