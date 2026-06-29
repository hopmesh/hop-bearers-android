package sh.hopme.bearers.wifidirect

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
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

// WifiDirectBearer — the Wi-Fi Direct (Wi-Fi P2P) transport as its OWN library (depends only on
// :bearer-core). It links Android<->Android when there is NO shared network/router: two devices form a
// peer-to-peer group over Wi-Fi P2P and then talk plain TCP. There is NO Apple counterpart — iOS has no
// Wi-Fi Direct — so this lib is Android-only. It is fully self-contained (its OWN ServerSocket on the
// group-owner side, its OWN Socket on the client side, its OWN WifiDirectLink) — nothing here is shared
// with :bearer-lan, but it speaks the SAME link grammar so the consumer sees identical link semantics
// and the bytes interop with the LAN bearer on the same node seam:
//
//   - 4-byte big-endian length prefix + a 1-byte frame type: HELLO 0x01, PING 0x02, PONG 0x03, DATA 0x10.
//   - HELLO carries the 16-byte nodeId so both ends learn the peer id (dedup + the consumer's key).
//   - 1 Hz PING keepalive feeds a liveness watchdog (~15 s dead) + a no-HELLO reaper (~5 s); PING/PONG
//     never surface to the consumer.
//   - DATA (0x10) is the consumer seam: Bearer.send wraps bytes in a DATA frame; inbound DATA ->
//     sink.linkBytes. One-pipe-per-peer dedup with the "greater nodeId dials" tiebreaker.
//
// TWO TIEBREAKERS (Wi-Fi P2P is unlike LAN). The DNS-SD instance name IS our nodeId as 32 hex chars
// (mirrors LanBearer's NSD instance name), so a browser learns the peer id WITHOUT connecting:
//   1. WHO INITIATES THE GROUP — only the GREATER nodeId calls connect() (matches BLE/LAN "greater
//      dials"). This is the pre-connect tiebreaker the task asks for: we CAN know the peer's nodeId
//      pre-connect (it is the DNS-SD instance name), so we use nodeIdGreater(myId, peerId) rather than
//      letting both attempt. Result: exactly one P2P group per pair, no mutual-invitation collision.
//   2. WHO DIALS THE SOCKET — the group-owner role is decided by the platform's P2P GO negotiation,
//      INDEPENDENT of nodeId. Whoever becomes group owner runs the ServerSocket accept loop; the other
//      dials Socket(groupOwnerAddress, WD_PORT). So `isDialer` reflects the actual TCP direction, not
//      the nodeId. Because (1) yields a single group per pair there is a single socket per pair, so the
//      post-HELLO greater-nodeId dedup is a safety net that rarely fires (kept for parity with LanBearer).
//
// THREADING: WifiP2pManager callbacks arrive on `handler`'s looper thread (an internal HandlerThread);
// the accept loop, per-link rx threads and per-link keepalive ScheduledExecutors run on their own
// threads. Every link/dedup-map mutation/read is guarded by `lock` (the same discipline as LanBearer).

internal const val WD_SERVICE_TYPE = "_hopwd._tcp" // distinct from LAN's _hoplan._tcp; P2P DNS-SD is its own channel

private const val WD_PING_MS = 1000L      // 1 Hz keepalive
private const val WD_DEAD_MS = 15_000L    // TCP is reliable; a generous liveness deadline
private const val WD_REAP_MS = 5_000L     // close a connection that never completes HELLO
private const val WD_MAX_FRAME = 4 * 1024 * 1024
private const val WD_DIAL_TIMEOUT_MS = 6_000 // P2P link setup is slower than LAN; allow more time
private const val WD_DISCOVER_MS = 15_000L   // re-trigger service discovery while not in a group
// Fixed TCP port the group owner listens on (so the client knows where to dial post-group). Distinct
// from the prod legacy WifiDirectBearer's 47474 (only one of legacy/shared runs, but keep it unambiguous).
private const val WD_PORT = 47475

// Wire frame types — byte-identical to :bearer-lan's LanLink and apple/HopBearers' LanBearer.
private const val W_HELLO = 0x01
private const val W_PING = 0x02
private const val W_PONG = 0x03
private const val W_DATA = 0x10

// ---- WifiDirectLink: one TCP socket, same framing/keepalive/HELLO grammar as :bearer-lan's LanLink ----
internal class WifiDirectLink(
    private val socket: Socket,
    val linkId: Long,
    val isDialer: Boolean,
    private val myId: ByteArray,
    private val onUp: (WifiDirectLink) -> Unit,
    private val onData: (WifiDirectLink, ByteArray) -> Unit,
    private val onClose: (WifiDirectLink) -> Unit,
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
        // HELLO first (same grammar as LAN/BLE): [0x01][16B nodeId][1B role][1B flags]
        sendFrame(byteArrayOf(W_HELLO.toByte()) + myId + byteArrayOf((if (isDialer) 1 else 0).toByte(), 0))
        Log.i(TAG, "wd channel-ready isDialer=$isDialer — sent HELLO")
        thread(name = "wd-rx") { readLoop() }
        sched.scheduleAtFixedRate({ tick() }, WD_PING_MS, WD_PING_MS, TimeUnit.MILLISECONDS)
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        if (!up && now - openedMs > WD_REAP_MS) { close("no-HELLO reap"); return }
        if (up && now - lastRxMs > WD_DEAD_MS) { close("liveness DEAD (silent ${now - lastRxMs}ms)"); return }
        txSeq++
        sendFrame(byteArrayOf(W_PING.toByte()) + u64(txSeq) + u64(now))
    }

    /// Bearer.send entry point: wrap the consumer's application bytes in a DATA frame (0x10) and send.
    fun sendData(bytes: ByteArray) {
        if (closed) return
        sendFrame(byteArrayOf(W_DATA.toByte()) + bytes)
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
                if (len < 1 || len > WD_MAX_FRAME) { close("bad len $len"); return }
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
            W_HELLO -> if (b.size >= 17 && !up) { // HELLO learns the peer id → surface linkUp
                peerId = b.copyOfRange(1, 17)
                up = true
                Log.i(TAG, "wd hello-recv peer=${peerId!!.toHex().take(8)} isDialer=$isDialer")
                onUp(this)
            }
            W_PING -> { // PING → PONG. seq is the peer's monotonic keepalive counter (never surfaced).
                if (b.size < 9) return
                rxSeq = u64dec(b, 1)
                sendFrame(byteArrayOf(W_PONG.toByte()) + b.copyOfRange(1, minOf(17, b.size)))
            }
            W_PONG -> { /* reverse-direction liveness; lastRxMs already bumped in readLoop */ }
            W_DATA -> onData(this, b.copyOfRange(1, b.size)) // DATA → consumer application bytes
            else -> { /* unknown frame type — ignore */ }
        }
    }

    fun close(why: String) {
        if (closed) return
        closed = true
        Log.i(TAG, "wd link-down ($why) peer=${peerId?.toHex()?.take(8)} isDialer=$isDialer")
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

// ---- WifiDirectBearer: Wi-Fi P2P discover/connect + one TCP ServerSocket, one-pipe-per-peer dedup -----
class WifiDirectBearer(private val ctx: Context, private val myId: ByteArray) : Bearer {
    override var sink: LinkSink? = null
    /// Short transport tag for the consumer's UI (Bearer contract). Wi-Fi Direct links surface as "Wi-Fi Direct".
    override val transportName = "Wi-Fi Direct"

    private val lock = Any()
    private val linksByPeerId = HashMap<String, WifiDirectLink>() // dedup: one survivor per peer
    private val linksByLinkId = HashMap<Long, WifiDirectLink>()    // send routing + linkUp/linkDown pairing
    private val dialing = HashSet<String>()                        // peerId-hex we've initiated connect() to
    private var nextLinkId = 1L

    // WifiP2pManager needs a Looper for its channel + callbacks; we own a private one (the bearer has no
    // app main thread of its own, unlike the prod WifiDirectBearer which is handed the node's Handler).
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var serviceRequest: WifiP2pDnsSdServiceRequest? = null
    private var server: ServerSocket? = null

    @Volatile private var connecting = false
    @Volatile private var groupFormed = false
    @Volatile private var dialedThisGroup = false
    @Volatile private var started = false

    private val myHex = myId.toHex()

    override fun start() {
        if (started) return
        if (!hasPermission()) { Log.w(TAG, "wd missing nearby/location permission — not starting"); return }
        val mgr = ctx.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager ?: run {
            Log.w(TAG, "wd WifiP2pManager unavailable"); return
        }
        started = true
        Log.i(TAG, "wd node-start myId=$myHex service=$WD_SERVICE_TYPE port=$WD_PORT")
        val ht = HandlerThread("wd-p2p").also { it.start() }
        handlerThread = ht
        val h = Handler(ht.looper)
        handler = h
        manager = mgr
        channel = mgr.initialize(ctx, ht.looper, null) ?: run {
            Log.w(TAG, "wd channel initialize failed"); return
        }
        // The accept server always listens on 0.0.0.0:WD_PORT, so when WE become group owner the p2p
        // interface IP is already covered and the client can dial us immediately (mirrors prod design).
        thread(name = "wd-start") { startListener() }
        setDiscoveryListeners()
        registerReceiver()
        activate()
        startDiscoverLoop()
    }

    override fun stop() {
        started = false
        receiver?.let { r -> runCatching { ctx.unregisterReceiver(r) } }; receiver = null
        val mgr = manager; val ch = channel
        if (mgr != null && ch != null) {
            runCatching { mgr.clearLocalServices(ch, null) }
            runCatching { mgr.clearServiceRequests(ch, null) }
            runCatching { mgr.stopPeerDiscovery(ch, null) }
        }
        serviceRequest = null
        runCatching { server?.close() }; server = null
        val all = synchronized(lock) { linksByPeerId.values.toList() }
        all.forEach { it.close("stop") }
        synchronized(lock) { dialing.clear() }
        handlerThread?.quitSafely(); handlerThread = null; handler = null
        manager = null; channel = null
    }

    override fun send(bytes: ByteArray, link: LinkId) {
        val l = synchronized(lock) { linksByLinkId[link] } // no-op if link closed/unknown
        l?.sendData(bytes)
    }

    private fun mint(): Long = synchronized(lock) { val id = nextLinkId; nextLinkId += 1; id }

    // One TCP server accepts every inbound link (when we're the group owner). Bound to the fixed WD_PORT
    // on all interfaces so the p2p interface is covered the moment a group forms.
    private fun startListener() {
        try {
            val s = ServerSocket()
            s.reuseAddress = true
            s.bind(InetSocketAddress(WD_PORT))
            server = s
            Log.i(TAG, "wd listening port=$WD_PORT name=${myHex.take(8)}")
            thread(name = "wd-accept") {
                while (true) {
                    val sock = try {
                        s.accept()
                    } catch (e: IOException) {
                        Log.i(TAG, "wd accept loop ended: ${e.message}")
                        break
                    }
                    Log.i(TAG, "wd inbound-connection (group owner acceptor)")
                    WifiDirectLink(sock, mint(), isDialer = false, myId, ::onUp, ::onData, ::onClose).start()
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "wd listener bind failed: ${e.message}")
        }
    }

    // ---- discovery callbacks (set once) ----
    private fun setDiscoveryListeners() {
        val mgr = manager ?: return; val ch = channel ?: return
        val h = handler ?: return
        mgr.setDnsSdResponseListeners(ch,
            { instanceName, _, srcDevice ->
                // instanceName is the peer's nodeId as 32 hex chars (mirror of LanBearer's NSD name).
                h.post {
                    val peerId = peerIdFromName(instanceName) ?: return@post
                    val peerHex = peerId.toHex()
                    if (peerHex == myHex) return@post                       // our own advertised service
                    if (!nodeIdGreater(myId, peerId)) return@post           // tiebreaker: greater initiates the group
                    if (connecting || groupFormed) return@post             // one P2P group at a time
                    synchronized(lock) {
                        if (linksByPeerId.containsKey(peerHex)) return@post // already linked
                        if (!dialing.add(peerHex)) return@post             // already connecting to this peer
                    }
                    Log.i(TAG, "wd discovered peer=${peerHex.take(8)} -> CONNECT (group init)")
                    connectTo(srcDevice.deviceAddress, peerHex)
                }
            },
            { _, _, _ -> /* TXT record — the port is fixed (WD_PORT), nothing to read */ },
        )
    }

    /// Advertise our service (instance name = our nodeId) + scan for peers'. This is what puts the
    /// 2.4 GHz radio to work; it runs for the whole lifetime of the bearer (the consumer decides whether
    /// to register this bearer at all — unlike the prod WifiDirectBearer there is no off-network gate).
    private fun activate() {
        val mgr = manager ?: return; val ch = channel ?: return
        if (!hasPermission()) return
        val info = WifiP2pDnsSdServiceInfo.newInstance(myHex, WD_SERVICE_TYPE, mapOf("port" to WD_PORT.toString()))
        runCatching { mgr.addLocalService(ch, info, null) }
        val req = WifiP2pDnsSdServiceRequest.newInstance()
        serviceRequest = req
        runCatching { mgr.addServiceRequest(ch, req, null) }
        runCatching { mgr.discoverServices(ch, null) }
        Log.i(TAG, "wd active (advertising + discovering)")
    }

    private fun connectTo(deviceAddress: String, peerHex: String) {
        val mgr = manager ?: return; val ch = channel ?: return
        if (!hasPermission()) { synchronized(lock) { dialing.remove(peerHex) }; return }
        val config = WifiP2pConfig().apply {
            this.deviceAddress = deviceAddress
            wps.setup = WpsInfo.PBC
        }
        connecting = true
        runCatching {
            mgr.connect(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { Log.i(TAG, "wd connect → ${peerHex.take(8)}") }
                override fun onFailure(reason: Int) {
                    connecting = false
                    synchronized(lock) { dialing.remove(peerHex) }
                    Log.w(TAG, "wd connect failed $reason peer=${peerHex.take(8)}")
                }
            })
        }.onFailure {
            connecting = false
            synchronized(lock) { dialing.remove(peerHex) }
        }
    }

    // ---- connection lifecycle ----
    private fun registerReceiver() {
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        }
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> onConnectionChanged()
                }
            }
        }
        receiver = r
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            ctx.registerReceiver(r, filter)
        }
    }

    private fun onConnectionChanged() {
        val mgr = manager ?: return; val ch = channel ?: return
        mgr.requestConnectionInfo(ch) { info ->
            if (!info.groupFormed) {
                // group disbanded → return to discovery; clear in-flight dial gates so we can re-connect.
                groupFormed = false; connecting = false; dialedThisGroup = false
                synchronized(lock) { dialing.clear() }
                return@requestConnectionInfo
            }
            groupFormed = true; connecting = false
            if (info.isGroupOwner) {
                // Our ServerSocket (0.0.0.0:WD_PORT) accepts the client — nothing to do here.
                Log.i(TAG, "wd group owner — awaiting client on $WD_PORT")
            } else if (!dialedThisGroup) {
                dialedThisGroup = true
                val host = info.groupOwnerAddress ?: return@requestConnectionInfo
                thread(name = "wd-dial") {
                    val sock = runCatching {
                        Socket().apply { connect(InetSocketAddress(host, WD_PORT), WD_DIAL_TIMEOUT_MS) }
                    }.getOrNull()
                    if (sock != null) {
                        Log.i(TAG, "wd dialed GO $host:$WD_PORT — wrapping link")
                        WifiDirectLink(sock, mint(), isDialer = true, myId, ::onUp, ::onData, ::onClose).start()
                    } else {
                        Log.w(TAG, "wd dial GO $host:$WD_PORT failed")
                        dialedThisGroup = false
                    }
                }
            }
        }
    }

    // ---- control loop: keep service discovery fresh while we're not already in a group ----
    private fun startDiscoverLoop() {
        val h = handler ?: return
        h.postDelayed(object : Runnable {
            override fun run() {
                val mgr = manager; val ch = channel
                if (started && mgr != null && ch != null && hasPermission() && !groupFormed) {
                    runCatching { mgr.discoverServices(ch, null) }
                }
                if (started) handler?.postDelayed(this, WD_DISCOVER_MS)
            }
        }, WD_DISCOVER_MS)
    }

    // ---- link lifecycle (callbacks arrive on rx / accept / dial threads; maps guarded by lock) --------

    private fun onUp(link: WifiDirectLink) { // HELLO completed: surface to sink, then dedup
        val peer = link.peerId ?: return
        val key = peer.toHex()
        synchronized(lock) {
            linksByLinkId[link.linkId] = link  // register for send routing + down pairing
            dialing.remove(key)                // the connect/dial for this peer has produced a live link
        }
        // Surface BEFORE dedup (LAN parity): if both legs of a duplicate pair ever come up, both surface,
        // then dedup closes the loser → the consumer sees that loser's linkDown.
        sink?.linkUp(link.linkId, if (link.isDialer) HopRole.DIALER else HopRole.ACCEPTOR, peer)
        var drop: WifiDirectLink? = null
        synchronized(lock) {
            val existing = linksByPeerId[key]
            if (existing == null || existing === link) {
                linksByPeerId[key] = link
            } else {
                val keepDialed = nodeIdGreater(myId, peer) // keep MY dialed channel iff I'm the greater id
                val keep = listOf(existing, link).firstOrNull { it.isDialer == keepDialed } ?: link
                drop = if (keep === link) existing else link
                linksByPeerId[key] = keep // set survivor BEFORE closing the dropped channel
                Log.i(TAG, "wd DEDUP kept isDialer=${keep.isDialer} peer=${key.take(8)}")
            }
        }
        drop?.close("dedup") // outside lock: close → onClose → sink.linkDown for the loser
    }

    private fun onData(link: WifiDirectLink, bytes: ByteArray) {
        sink?.linkBytes(link.linkId, bytes) // one DATA frame → consumer
    }

    private fun onClose(link: WifiDirectLink) { // identity-checked removal
        val peer = link.peerId
        val wasUp: Boolean
        synchronized(lock) {
            wasUp = linksByLinkId.remove(link.linkId) != null // true iff linkUp had fired
            if (peer != null) {
                val key = peer.toHex()
                if (linksByPeerId[key] === link) linksByPeerId.remove(key)
                dialing.remove(key)
            }
        }
        if (wasUp) sink?.linkDown(link.linkId) // pair every linkDown with a prior linkUp
    }

    private fun hasPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        return ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
    }
}

/// The DNS-SD instance name is the peer's 32-hex-char nodeId. Parse it back to 16 bytes (mirror
/// LanBearer's peerIdFromName). Returns null for any name that is not exactly 32 hex chars.
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
