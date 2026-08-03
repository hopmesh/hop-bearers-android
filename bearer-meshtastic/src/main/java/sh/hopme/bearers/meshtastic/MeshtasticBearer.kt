package sh.hopme.bearers.meshtastic

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import sh.hop.Bearer
import sh.hop.HopRole
import sh.hop.LinkId
import sh.hop.LinkSink
import sh.hop.TAG
import sh.hop.nodeIdGreater
import sh.hop.toHex
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

// MeshtasticBearer - the Hop transport that RELAYS through a connected Meshtastic radio, the Android mirror
// of bearers/apple/HopBearerMeshtastic. A phone pairs with a nearby Meshtastic device over BLE; that device
// is a gateway into a LoRa mesh where every radio relays packets hop by hop. This bearer turns that mesh
// into a Hop transport: each remote Meshtastic node also running Hop becomes a peer, Hop link frames are
// fragmented into LoRa-sized Meshtastic packets on a private app port, and the mesh carries them. The
// consumer sees identical linkUp/linkBytes/linkDown semantics, keyed on the peer's 16-byte nodeId.
//
// TESTABILITY. All Meshtastic PROTOCOL logic (protobuf, fragmentation, the Hop link-frame grammar, dedup)
// lives in MeshtasticWire.kt and is unit-tested with no radio. This file owns the LINK STATE MACHINE and
// drives a `MeshtasticRadio` seam that moves raw ToRadio/FromRadio protobuf frames. In production the seam
// is AndroidMeshtasticRadio (the BluetoothGatt client, DEVICE-BOUND and excluded from the coverage
// denominator like :bearer-lan's NSD glue). In tests it is a fake radio, so the whole state machine runs on
// a plain JVM with an injected clock.
//
// THREADING. One single-thread executor (`work`) owns every link/reassembly/timer mutation, so the state
// machine is single-threaded end to end and needs no locks (mirrors the Apple bearer's serial queue). The
// radio delivers inbound frames onto this executor.

/** The seam between the state machine and the physical Meshtastic device. Moves opaque protobuf frames. */
internal interface MeshtasticRadio {
    var onConnect: (() -> Unit)?
    var onDisconnect: (() -> Unit)?
    var onFromRadio: ((ByteArray) -> Unit)?
    fun start()
    fun stop()
    fun sendToRadio(bytes: ByteArray)
}

/** One logical link to a remote Meshtastic node (at most one per node num). */
internal class MeshLink(
    val linkId: Long,
    val nodeNum: Long,
    var peerId: ByteArray,
    /** True iff MY nodeId is the greater one (the Noise initiator). */
    val isGreater: Boolean,
    nowMs: Long,
) {
    var up = false
    var surfaced = false
    var lastRxMs = nowMs
    var lastPingMs = nowMs
    var txSeq = 0L
    val role: HopRole get() = if (isGreater) HopRole.DIALER else HopRole.ACCEPTOR
    val peerShort: String get() = peerId.toHex().take(8)
}

class MeshtasticBearer internal constructor(
    private val myId: ByteArray,
    private val radio: MeshtasticRadio,
) : Bearer {
    /** Production constructor: talk to a real Meshtastic radio over BLE. */
    constructor(context: Context, myId: ByteArray) : this(myId, AndroidMeshtasticRadio(context))

    override var sink: LinkSink? = null
    /// Short transport tag for the consumer's UI (Bearer contract). Meshtastic/LoRa links surface as "LoRa".
    override val transportName = "LoRa"

    private var work: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val linksByNode = HashMap<Long, MeshLink>()
    private val linksByLinkId = HashMap<Long, MeshLink>()
    private val reassembler = MeshReassembler()
    private var nextLinkId = 1L
    private var nextMsgId = 1
    private var nextPktId = 1L
    private var myNodeNum: Long? = null
    private var stopped = false
    private var lastBeaconMs = 0L
    private var configNonce = 1L

    override fun start() {
        if (work.isShutdown) work = Executors.newSingleThreadScheduledExecutor()
        submit {
            stopped = false
            radio.onConnect = { submit { onRadioConnected() } }
            radio.onDisconnect = { submit { onRadioDisconnected() } }
            radio.onFromRadio = { bytes -> submit { onFromRadio(bytes) } }
            radio.start()
        }
        try {
            work.scheduleAtFixedRate(
                { runCatching { runMaintenance(System.currentTimeMillis()) } },
                MESH_PING_MS, MESH_PING_MS, TimeUnit.MILLISECONDS,
            )
        } catch (_: RejectedExecutionException) {
            stopped = true
        }
        Log.i(TAG, "mesh node-start myId=${myId.toHex()} port=$MESH_HOP_PORTNUM")
    }

    override fun stop() {
        submit {
            stopped = true
            for (link in ArrayList(linksByNode.values)) teardown(link, "stop")
            radio.stop()
        }
        work.shutdown()
    }

    override fun send(bytes: ByteArray, link: LinkId) {
        submit {
            val l = linksByLinkId[link] ?: return@submit
            shipFrame(MeshFrame.data(bytes), l.nodeNum)
        }
    }

    // ---- Radio callbacks (all on the work thread) -----------------------------------------------------

    private fun onRadioConnected() {
        if (stopped) return
        Log.i(TAG, "mesh radio-connected, requesting config")
        configNonce += 1
        radio.sendToRadio(MeshtasticProto.encodeWantConfig(configNonce))
        broadcastHello()
    }

    private fun onRadioDisconnected() {
        for (link in ArrayList(linksByNode.values)) teardown(link, "radio down")
        myNodeNum = null
    }

    private fun onFromRadio(bytes: ByteArray) {
        if (stopped) return
        when (val inbound = MeshtasticProto.decodeFromRadio(bytes)) {
            is MeshInbound.MyNodeNum -> {
                if (myNodeNum != inbound.num) Log.i(TAG, "mesh my-node-num=${inbound.num}")
                myNodeNum = inbound.num
            }
            is MeshInbound.HopData -> {
                if (inbound.from == 0L || inbound.from == myNodeNum) return  // ignore our own echoes
                val body = reassembler.accept(inbound.from, inbound.payload, System.currentTimeMillis())
                    ?: return
                handleFrame(inbound.from, body)
            }
            null -> {}
        }
    }

    // ---- Hop link-frame handling ----------------------------------------------------------------------

    private fun handleFrame(node: Long, body: ByteArray) {
        if (body.isEmpty()) return
        linksByNode[node]?.let { it.lastRxMs = System.currentTimeMillis() }
        when (body[0].toInt() and 0xff) {
            M_HELLO -> MeshFrame.helloPeerId(body)?.let { onHello(node, it) }
            M_PING -> {
                val echo = body.copyOfRange(1, minOf(17, body.size))
                shipFrame(MeshFrame.pong(echo), node)
            }
            M_PONG -> { /* liveness only */ }
            M_DATA -> {
                val l = linksByNode[node] ?: return
                if (l.up) sink?.linkBytes(l.linkId, body.copyOfRange(1, body.size))
            }
            else -> { /* unknown */ }
        }
    }

    private fun onHello(node: Long, peerId: ByteArray) {
        if (peerId.contentEquals(myId)) return   // our own HELLO reflected by the mesh
        linksByNode[node]?.let { it.peerId = peerId; it.lastRxMs = System.currentTimeMillis(); return }
        val isGreater = meshKeepGreaterLeg(nodeIdGreater(myId, peerId))
        val link = MeshLink(mint(), node, peerId, isGreater, System.currentTimeMillis())
        link.up = true
        link.surfaced = true
        linksByNode[node] = link
        linksByLinkId[link.linkId] = link
        Log.i(TAG, "mesh hello-recv peer=${link.peerShort} node=$node greater=$isGreater")
        // Answer with a unicast HELLO so the peer learns us even if it missed our broadcast beacon.
        shipFrame(MeshFrame.hello(myId, isGreater), node)
        sink?.linkUp(link.linkId, link.role, peerId)
    }

    // ---- Outbound -------------------------------------------------------------------------------------

    private fun shipFrame(frame: ByteArray, dest: Long) {
        val msgId = nextMsgId; nextMsgId = (nextMsgId + 1) and 0xffff
        val frags = meshFragment(frame, msgId) ?: run {
            Log.w(TAG, "mesh frame too large to fragment (${frame.size} bytes)"); return
        }
        val from = myNodeNum ?: 0L
        for (frag in frags) {
            val id = nextPktId; nextPktId = (nextPktId + 1) and 0xffffffffL
            radio.sendToRadio(MeshtasticProto.encodeToRadioPacket(from, dest, id, 3, frag))
        }
    }

    private fun broadcastHello() {
        shipFrame(MeshFrame.hello(myId, false), MESH_BROADCAST_ADDR)
        lastBeaconMs = System.currentTimeMillis()
    }

    // ---- Maintenance (beacon, per-link keepalive, dead reap) ------------------------------------------

    private fun runMaintenance(now: Long) {
        if (stopped) return
        reassembler.evictStale(now)
        if (now - lastBeaconMs >= MESH_PING_MS) broadcastHello()
        for (link in ArrayList(linksByNode.values)) {
            if (now - link.lastRxMs > MESH_DEAD_MS) { teardown(link, "liveness DEAD"); continue }
            if (now - link.lastPingMs >= MESH_PING_MS) {
                link.lastPingMs = now
                link.txSeq += 1
                shipFrame(MeshFrame.ping(link.txSeq, now), link.nodeNum)
            }
        }
    }

    // ---- Teardown -------------------------------------------------------------------------------------

    private fun teardown(link: MeshLink, why: String) {
        linksByNode.remove(link.nodeNum)
        linksByLinkId.remove(link.linkId)
        reassembler.forget(link.nodeNum)
        Log.i(TAG, "mesh link-down ($why) peer=${link.peerShort} node=${link.nodeNum}")
        if (link.surfaced) sink?.linkDown(link.linkId)
    }

    private fun mint(): Long { val id = nextLinkId; nextLinkId += 1; return id }

    private fun submit(block: () -> Unit) {
        try { work.execute { runCatching { block() } } } catch (_: RejectedExecutionException) {}
    }

    // ---- Test seams (bearer-meshtastic unit tests) ----------------------------------------------------
    // These run the REAL production paths on the work thread with a fake radio + injected clock, mirroring
    // the Apple DEBUG seams. They add NO behavior.

    /** Block until the work queue drains, so a test observes the effect of prior events. */
    internal fun awaitIdle() {
        val done = java.util.concurrent.CountDownLatch(1)
        work.execute { done.countDown() }
        done.await(2, TimeUnit.SECONDS)
    }

    internal fun runMaintenanceForTest(nowMs: Long) { submit { runMaintenance(nowMs) }; awaitIdle() }
    internal fun linkCountForTest(): Int { awaitIdle(); return linksByNode.size }
    internal fun myNodeNumForTest(): Long? { awaitIdle(); return myNodeNum }
}

/** The REAL Meshtastic device connection: a BluetoothGatt client that scans for a Meshtastic radio,
 *  connects over its GATT service, and moves ToRadio/FromRadio protobuf frames. DEVICE-BOUND and excluded
 *  from the coverage denominator (Robolectric cannot drive a real GATT peer); exercised on device only.
 *
 *  Meshtastic BLE protocol:
 *    Service          6ba1b218-15a8-461f-9fa8-5dcae273eafd
 *    ToRadio  (write) f75c76d2-129e-4dad-a1dd-7866124401e7
 *    FromRadio (read) 2c55e69e-4993-11ed-b878-0242ac120002   each read returns one FromRadio, empty = drained
 *    FromNum (notify) ed9da18c-a800-4f66-a670-aa7547e34453   notifies when new FromRadio frames are queued
 */
internal class AndroidMeshtasticRadio(private val context: Context) : MeshtasticRadio {
    override var onConnect: (() -> Unit)? = null
    override var onDisconnect: (() -> Unit)? = null
    override var onFromRadio: ((ByteArray) -> Unit)? = null

    private val serviceUuid = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273eafd")
    private val toRadioUuid = UUID.fromString("f75c76d2-129e-4dad-a1dd-7866124401e7")
    private val fromRadioUuid = UUID.fromString("2c55e69e-4993-11ed-b878-0242ac120002")
    private val fromNumUuid = UUID.fromString("ed9da18c-a800-4f66-a670-aa7547e34453")

    private var adapter: BluetoothAdapter? = null
    private var gatt: BluetoothGatt? = null
    private var toRadio: BluetoothGattCharacteristic? = null
    private var fromRadio: BluetoothGattCharacteristic? = null
    private var running = false

    @SuppressLint("MissingPermission")
    override fun start() {
        running = true
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return
        adapter = mgr.adapter
        val scanner = adapter?.bluetoothLeScanner ?: return
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(serviceUuid)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, scanCallback)
    }

    @SuppressLint("MissingPermission")
    override fun stop() {
        running = false
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        runCatching { gatt?.disconnect(); gatt?.close() }
        gatt = null; toRadio = null; fromRadio = null
    }

    @SuppressLint("MissingPermission")
    override fun sendToRadio(bytes: ByteArray) {
        val g = gatt ?: return
        val ch = toRadio ?: return
        @Suppress("DEPRECATION")
        run { ch.value = bytes; g.writeCharacteristic(ch) }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (gatt != null) return
            adapter?.bluetoothLeScanner?.stopScan(this)
            gatt = result.device.connectGatt(context, false, gattCallback)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> g.discoverServices()
                BluetoothProfile.STATE_DISCONNECTED -> {
                    toRadio = null; fromRadio = null; gatt = null
                    onDisconnect?.invoke()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(serviceUuid) ?: return
            toRadio = svc.getCharacteristic(toRadioUuid)
            fromRadio = svc.getCharacteristic(fromRadioUuid)
            svc.getCharacteristic(fromNumUuid)?.let { g.setCharacteristicNotification(it, true) }
            if (toRadio != null && fromRadio != null) {
                onConnect?.invoke()
                fromRadio?.let { g.readCharacteristic(it) }   // drain whatever is already queued
            }
        }

        // A FromNum notify means new FromRadio frames are queued; kick off a read to drain them. Both the
        // legacy (< API 33) and the value-carrying (API 33+) callbacks are overridden so this works on
        // every Android version the module supports.
        @SuppressLint("MissingPermission")
        private fun onFromNum(g: BluetoothGatt, uuid: UUID) {
            if (uuid == fromNumUuid) fromRadio?.let { g.readCharacteristic(it) }
        }

        // Each FromRadio read yields one protobuf frame; an empty value means the radio's queue is drained.
        @SuppressLint("MissingPermission")
        private fun onFromRadioValue(g: BluetoothGatt, uuid: UUID, value: ByteArray?) {
            if (uuid != fromRadioUuid) return
            if (value != null && value.isNotEmpty()) {
                onFromRadio?.invoke(value)
                fromRadio?.let { g.readCharacteristic(it) }   // keep reading until an empty value drains it
            }
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) =
            onFromNum(g, ch.uuid)

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) =
            onFromNum(g, ch.uuid)

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            onFromRadioValue(g, ch.uuid, ch.value)
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) = onFromRadioValue(g, ch.uuid, value)
    }
}
