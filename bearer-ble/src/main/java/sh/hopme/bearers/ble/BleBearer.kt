package sh.hopme.bearers.ble

import sh.hop.Bearer
import sh.hop.LinkId
import sh.hop.HopRole
import sh.hop.LinkSink
import sh.hop.TAG
import sh.hop.appInBackground
import sh.hop.nodeIdGreater
import sh.hop.toHex
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

// BleBearer — the PROVEN dual-role BLE transport (ble-lab/SPEC.md §9), re-seamed behind the
// Bearer/LinkSink contract so the clean-room app and (later) the production app share ONE transport.
// This is the Android mirror of apple/HopBearers' BleBearer.swift: a re-seam of the old all-in-one
// Ble.kt, NOT a re-tune.
//
//   - KEPT IN THE TRANSPORT (unchanged behavior): 4-byte BE framing; the 1 Hz PING *as a keepalive*
//     that feeds the watchdog + STATUS counters; the adaptive liveness watchdog (DEAD_MS/DEAD_BG_MS)
//     + no-HELLO reaper; the HELLO identity handshake; one-pipe-per-peer dedup (linksByPeerId +
//     greater-nodeId keep rule); scan-mode downshift; the beacon cycler; and the Central redial logic
//     INCLUDING the addrToPeerId redial-storm suppression + backoff + scan throttle.
//
//   - LIFTED OUT TO THE CONSUMER: the per-second PROOF counters + `PROOF …` log line. Those now live
//     in the clean-room ProofSink (app module), which pings over DATA frames via Bearer.send. The
//     transport drives the sink: linkUp on HELLO, linkBytes on a DATA frame, linkDown on close. The
//     keepalive PING/PONG (0x02/0x03) frames stay transport-internal and NEVER surface as linkBytes.
//
// Grep the proof with:  adb logcat -s HOPLOG
//
// TAG, appInBackground, the ByteArray.toHex() helper, nodeIdGreater (the dial tiebreaker), and
// randomNodeId now live in :bearer-core — they are transport-neutral and shared with LAN/relay. This
// file imports them and keeps ONLY the BLE-specific constants + types below.

internal val SERVICE_UUID: ParcelUuid = ParcelUuid.fromString("7ED70001-3C2A-4F19-9B8E-1A2B3C4D5E6F")
internal val ENDPOINT_CHAR: UUID = UUID.fromString("7ED70002-3C2A-4F19-9B8E-1A2B3C4D5E6F")
internal const val MFG_ID = 0xFFFF

// iBeacon (Layer C) — the iOS *relaunch* signal. Byte-matches iOS BeaconWake.swift BEACON_UUID.
internal val BEACON_UUID: UUID = UUID.fromString("7ED7BEAC-3C2A-4F19-9B8E-1A2B3C4D5E6F") // == iOS BEACON_UUID
internal const val APPLE_COMPANY_ID = 0x004C
internal const val BEACON_CYCLE_MS = 300_000L   // ~5 min: floor for CoreLocation relaunch rate-limit
internal const val BEACON_EXIT_GAP_MS = 35_000L // > iOS ~30 s exit-debounce, so stop→start makes a clean enter

internal fun iBeaconPayload(uuid: UUID, major: Int, minor: Int, measuredPowerDbm: Int): ByteArray {
    val b = java.nio.ByteBuffer.allocate(23)          // ByteBuffer is big-endian by default
    b.put(0x02).put(0x15)                             // subtype + length(0x15=21)
    b.putLong(uuid.mostSignificantBits)              // UUID high 8 bytes (network order)
    b.putLong(uuid.leastSignificantBits)             // UUID low 8 bytes
    b.putShort(major.toShort())                      // major BE
    b.putShort(minor.toShort())                      // minor BE
    b.put(measuredPowerDbm.toByte())                 // -59 -> 0xC5
    return b.array()
}
internal const val PING_MS = 1000L
internal const val DEAD_MS = 5000L
internal const val DEAD_BG_MS = 15_000L
internal const val REAP_MS = 3000L
internal const val MAX_DIALS = 2
internal const val DIAL_TIMEOUT_MS = 12_000L
internal const val LOST_MS = 30_000L
internal const val CLOSE_GATT_AFTER_L2CAP = false // R5: free GATT slot after L2CAP up — OEM-risky; verify before enabling

// Wire frame types (SPEC §4). DATA (0x10) is the consumer seam: Bearer.send wraps the consumer's
// application bytes in a DATA frame, and an inbound DATA frame is delivered via sink.linkBytes. The
// HELLO/PING/PONG types are the transport's own handshake + keepalive and never reach the consumer.
internal const val FRAME_HELLO = 0x01
internal const val FRAME_PING = 0x02
internal const val FRAME_PONG = 0x03
internal const val FRAME_DATA = 0x10

// ---- One L2CAP link over a BluetoothSocket: 4-byte BE framing, 1 Hz PING (keepalive),
//      adaptive liveness watchdog, 3 s half-open reaper. SPEC §4/§5/§8. ----
internal class Link(
    private val socket: BluetoothSocket,
    val linkId: Long,                                   // monotonic id minted by the bearer; the sink's key
    val isDialer: Boolean,
    private val myId: ByteArray,
    private val onUp: (Link) -> Unit,
    private val onData: (Link, ByteArray) -> Unit,
    private val onClose: (Link) -> Unit,
) {
    @Volatile
    var peerId: ByteArray? = null

    @Volatile
    var up = false

    @Volatile
    private var becameUpMs = 0L

    @Volatile
    private var lastRxMs = System.currentTimeMillis()
    private val openedMs = System.currentTimeMillis()
    private var ewmaGapMs = 1000.0
    private var txSeq = 0L
    private var rxSeq = 0L
    private var rxBytes = 0L
    private var txBytes = 0L

    @Volatile
    private var closed = false
    private val out = socket.outputStream
    private val inp = socket.inputStream
    private val writeLock = Any()
    private val sched = Executors.newSingleThreadScheduledExecutor()

    // §6: a link is "stable" once it has stayed UP for >= 30 s.
    fun stableUp(): Boolean =
        up && becameUpMs != 0L && System.currentTimeMillis() - becameUpMs >= 30_000L

    fun start() {
        // HELLO first (SPEC §3.3): [0x01][16B nodeId][1B role][1B flags]
        sendFrame(byteArrayOf(FRAME_HELLO.toByte()) + myId + byteArrayOf((if (isDialer) 1 else 0).toByte(), 0))
        Log.i(TAG, "LINK OPENING isDialer=$isDialer reaper=${REAP_MS}ms — sent HELLO")
        thread(name = "l2cap-rx") { readLoop() }
        sched.scheduleAtFixedRate({ tick() }, PING_MS, PING_MS, TimeUnit.MILLISECONDS)
    }

    private fun deadLimit(): Long { // R7: adaptive deadline
        val base = if (appInBackground) DEAD_BG_MS else DEAD_MS
        return maxOf(base, (3.0 * ewmaGapMs).toLong())
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        if (!up && now - openedMs > REAP_MS) {
            close("no-HELLO reap")
            return
        }
        if (up && now - lastRxMs > deadLimit()) {
            close("liveness DEAD (silent ${now - lastRxMs}ms > ${deadLimit()}ms)")
            return
        }
        // §5: the keepalive PING with the next monotonic seq, 1 Hz. It feeds the watchdog + STATUS
        // counters and the reverse-direction liveness; the per-second PROOF line moved to ProofSink.
        txSeq++
        sendFrame(byteArrayOf(FRAME_PING.toByte()) + u64(txSeq) + u64(now))
    }

    /// Bearer.send entry point: wrap the consumer's application bytes in a DATA frame (0x10) and send.
    fun sendData(bytes: ByteArray) {
        if (closed) return
        sendFrame(byteArrayOf(FRAME_DATA.toByte()) + bytes)
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
            synchronized(writeLock) {
                out.write(hdr); out.write(body); out.flush()
            }
            txBytes += (4 + n).toLong()
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
                if (len < 1 || len > 4 * 1024 * 1024) {
                    close("bad len $len")
                    return
                }
                val body = ByteArray(len)
                readFully(body, len)
                val now = System.currentTimeMillis()
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
                becameUpMs = System.currentTimeMillis()
                Log.i(TAG, "LINK UP isDialer=$isDialer peer=${peerId!!.toHex().take(8)} — HELLO both ways")
                onUp(this)
            }
            FRAME_PING -> { // PING → PONG. seq is the peer's monotonic keepalive counter.
                if (b.size < 9) return // mirror Apple's `guard b.count >= 9`; harden vs malformed PING
                val seq = u64dec(b, 1)
                if (rxSeq != 0L && seq != rxSeq + 1) {
                    Log.w(TAG, "counter gap $rxSeq -> $seq (peer=${peerId?.toHex()?.take(8)})")
                } else if (seq > rxSeq) {
                    // Peer bytes advanced: log the moment the peer's counter steps forward.
                    Log.i(
                        TAG,
                        "RX peer counter advanced rx=$seq peer=${peerId?.toHex()?.take(8)} rxBytes=$rxBytes",
                    )
                }
                rxSeq = seq
                sendFrame(byteArrayOf(FRAME_PONG.toByte()) + b.copyOfRange(1, minOf(17, b.size)))
            }
            FRAME_PONG -> { /* PONG: reverse-direction liveness; lastRxMs already bumped in readLoop */ }
            FRAME_DATA -> onData(this, b.copyOfRange(1, b.size)) // DATA → consumer application bytes
            else -> { /* unknown frame type — ignore */ }
        }
    }

    fun close(why: String) {
        if (closed) return
        closed = true
        Log.i(TAG, "LINK CLOSED ($why) isDialer=$isDialer peer=${peerId?.toHex()?.take(8)}")
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

// ---- ACCEPTOR (peripheral): session-stable L2CAP listener + one GATT read char + advertiser.
//      SPEC §3.1 / §7.1. ----
@SuppressLint("MissingPermission")
internal class Peripheral(
    private val ctx: Context,
    private val myId: ByteArray,
    private val mintLinkId: () -> Long,
    private val onLink: (Link) -> Unit,
    private val onData: (Link, ByteArray) -> Unit,
    private val onClose: (Link) -> Unit,
) {
    private val adapter =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var server: BluetoothServerSocket? = null

    @Volatile
    private var psm = 0
    private var gattServer: BluetoothGattServer? = null
    private var advSet: AdvertisingSet? = null

    private var beaconSet: AdvertisingSet? = null
    private val beaconSched = Executors.newSingleThreadScheduledExecutor()
    private val beaconCb = object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
            beaconSet = set; Log.i(TAG, "BEACON started status=$status txPower=$txPower")
        }
        override fun onAdvertisingSetStopped(set: AdvertisingSet?) {
            beaconSet = null; Log.i(TAG, "BEACON stopped")
        }
    }

    fun start() {
        val s = adapter.listenUsingInsecureL2capChannel() // INSECURE LE CoC, no bonding
        server = s
        psm = s.psm
        Log.i(TAG, "PERIPHERAL listening psm=$psm myId=${myId.toHex().take(8)}")
        thread(name = "l2cap-accept") {
            while (true) {
                val sock = try {
                    s.accept()
                } catch (e: IOException) {
                    Log.i(TAG, "ACCEPT loop ended: ${e.message}")
                    break
                }
                Log.i(TAG, "ACCEPTED inbound L2CAP — wrapping link (reaper armed)")
                Link(sock, mintLinkId(), isDialer = false, myId, onLink, onData, onClose).start()
            }
        }
        startGattServer() // R10: advertise only from onServiceAdded
        startBeacon()
        startBeaconCycler()
    }

    fun stop() {
        try { gattServer?.close() } catch (_: Exception) {}
        gattServer = null
        try { server?.close() } catch (_: Exception) {}
        server = null
        try { advSet?.let { adapter.bluetoothLeAdvertiser?.stopAdvertisingSet(advCb) } } catch (_: Exception) {}
        advSet = null
        try { beaconSet?.let { adapter.bluetoothLeAdvertiser?.stopAdvertisingSet(beaconCb) } } catch (_: Exception) {}
        beaconSet = null
    }

    private fun startGattServer() {
        val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        gattServer = mgr.openGattServer(ctx, object : BluetoothGattServerCallback() {
            override fun onServiceAdded(status: Int, service: BluetoothGattService) { // R10
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "GATT service added — starting advertiser")
                    startAdvertise()
                } else {
                    Log.w(TAG, "GATT addService failed status=$status")
                }
            }

            override fun onCharacteristicReadRequest(
                d: BluetoothDevice,
                reqId: Int,
                off: Int,
                ch: BluetoothGattCharacteristic,
            ) {
                val v = byteArrayOf((psm ushr 8).toByte(), psm.toByte()) + myId // [2B PSM][16B id]
                Log.i(TAG, "GATT read → returning psm=$psm to ${d.address}")
                gattServer?.sendResponse(d, reqId, BluetoothGatt.GATT_SUCCESS, 0, v)
            }
        })
        val svc = BluetoothGattService(SERVICE_UUID.uuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        svc.addCharacteristic(
            BluetoothGattCharacteristic(
                ENDPOINT_CHAR,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ,
            ),
        )
        gattServer?.addService(svc) // async → onServiceAdded
    }

    private val advCb = object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
            advSet = set
            Log.i(TAG, "ADVERTISING started status=$status txPower=$txPower prefix=${myId.copyOfRange(0, 6).toHex()}")
        }

        override fun onAdvertisingSetStopped(set: AdvertisingSet?) {
            advSet = null
            Log.i(TAG, "ADVERTISING stopped")
        }
    }

    // §7.1: idempotent self-heal — no-op while live, recovers a wedged advertiser.
    fun startAdvertise() {
        if (advSet != null) return
        val adv = adapter.bluetoothLeAdvertiser ?: return
        val params = AdvertisingSetParameters.Builder()
            .setLegacyMode(true).setConnectable(true).setScannable(true)
            .setInterval(AdvertisingSetParameters.INTERVAL_MEDIUM)
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MEDIUM).build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(SERVICE_UUID)
            .addManufacturerData(MFG_ID, myId.copyOfRange(0, 6)) // 6-byte prefix
            .build()
        adv.startAdvertisingSet(params, data, null, null, null, advCb)
    }

    fun startBeacon() {
        if (beaconSet != null) return
        val adv = adapter.bluetoothLeAdvertiser ?: return
        if (!adapter.isMultipleAdvertisementSupported) {     // controller can't run 2 sets at once
            Log.w(TAG, "multi-advertisement UNSUPPORTED — iBeacon skipped (time-slice fallback not implemented)")
            return
        }
        val params = AdvertisingSetParameters.Builder()
            .setLegacyMode(true)        // REQUIRED: iOS CoreLocation only detects legacy-PDU iBeacons
            .setConnectable(false).setScannable(false)
            .setInterval(AdvertisingSetParameters.INTERVAL_MEDIUM)
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MEDIUM).build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false).setIncludeTxPowerLevel(false)
            .addManufacturerData(APPLE_COMPANY_ID, iBeaconPayload(BEACON_UUID, 1, 1, -59)).build()
        adv.startAdvertisingSet(params, data, null, null, null, beaconCb)
    }

    // Manufacture iOS region exit→enter so a force-quit iOS app that is sitting adjacent gets relaunched.
    fun startBeaconCycler() {
        beaconSched.scheduleAtFixedRate({
            try {
                Log.i(TAG, "BEACON cycle: stop (force iOS region exit)")
                adapter.bluetoothLeAdvertiser?.stopAdvertisingSet(beaconCb); beaconSet = null
                beaconSched.schedule({ startBeacon() }, BEACON_EXIT_GAP_MS, TimeUnit.MILLISECONDS)
            } catch (_: Exception) {}
        }, BEACON_CYCLE_MS, BEACON_CYCLE_MS, TimeUnit.MILLISECONDS)
    }
}

// ---- DIALER (central): one persistent scan → connectGatt → read PSM+id →
//      createInsecureL2capChannel → connect. SPEC §3.2 / §7.2 / §7.3. ----
@SuppressLint("MissingPermission")
internal class Central(
    private val ctx: Context,
    private val myId: ByteArray,
    private val mintLinkId: () -> Long,
    private val onLink: (Link) -> Unit,
    private val onData: (Link, ByteArray) -> Unit,
    private val onClose: (Link) -> Unit,
    private val haveLinkTo: (ByteArray) -> Boolean,
    private val haveLinkToPrefix: (ByteArray) -> Boolean,
) {
    private val adapter =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val main = Handler(Looper.getMainLooper())
    private val inFlight = mutableSetOf<String>() // R2: short-lived, MAC-keyed
    private val backoff = mutableMapOf<String, Long>() // R2: prefix-hex (stable) or MAC
    private val addrToBkey = HashMap<String, String>() // MAC → backoff key (prefix once known)
    private val addrToPeerId = HashMap<String, ByteArray>() // MAC → resolved peerId; dial-suppression for prefix-less (macOS/iOS) adverts
    private val pendingWaits = mutableSetOf<String>() // R4: one wait per MAC
    private val gattByAddr = HashMap<String, BluetoothGatt>()
    private val scanStarts = ArrayDeque<Long>() // R9: 30 s sliding window of startScan times
    private var scanning = false
    private var currentMode = -1

    fun start() = applyScan(ScanSettings.SCAN_MODE_LOW_LATENCY)

    fun stop() {
        try { if (scanning) adapter.bluetoothLeScanner?.stopScan(scanCb) } catch (_: Exception) {}
        scanning = false
        currentMode = -1
        synchronized(gattByAddr) {
            gattByAddr.values.forEach { try { it.close() } catch (_: Exception) {} }
            gattByAddr.clear()
        }
        inFlight.clear()
    }

    fun requestScanMode(mode: Int, debounceMs: Long) { // R9: hysteresis (Node passes 10 s down / 2 s up)
        main.postDelayed({ if (mode != currentMode) applyScan(mode) }, debounceMs)
    }

    private fun applyScan(mode: Int) { // R9: never the 5th startScan in any 30 s window
        val now = System.currentTimeMillis()
        while (scanStarts.isNotEmpty() && now - scanStarts.first() > 30_000) scanStarts.removeFirst()
        if (scanStarts.size >= 4) {
            val wait = 30_000 - (now - scanStarts.first()) + 100
            Log.w(TAG, "SCAN start deferred ${wait}ms (throttle guard)")
            main.postDelayed({ applyScan(mode) }, wait)
            return
        }
        val sc = adapter.bluetoothLeScanner ?: return
        if (scanning) sc.stopScan(scanCb)
        val filters = listOf(ScanFilter.Builder().setServiceUuid(SERVICE_UUID).build())
        sc.startScan(filters, ScanSettings.Builder().setScanMode(mode).build(), scanCb)
        scanning = true
        currentMode = mode
        scanStarts.addLast(now)
        Log.i(TAG, "SCAN started mode=$mode (0=LOW_POWER 1=BALANCED 2=LOW_LATENCY)")
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(type: Int, r: ScanResult) {
            val pre = r.scanRecord?.getManufacturerSpecificData(MFG_ID)
                ?.let { if (it.size >= 6) it.copyOfRange(0, 6) else null }
            val dev = r.device
            val dialNow = if (pre != null) nodeIdGreater(myId.copyOfRange(0, 6), pre) else true // §2.1
            if (dialNow) {
                tryDial(dev, pre)
            } else if (pendingWaits.add(dev.address)) { // R4: one wait per peer
                main.postDelayed({
                    pendingWaits.remove(dev.address)
                    if (pre != null && haveLinkToPrefix(pre)) return@postDelayed // R4: gate on link map
                    Log.i(TAG, "WAIT-TIMEOUT fired → dialing ${dev.address}")
                    tryDial(dev, pre)
                }, 4000L + (0..1000L).random())
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "SCAN failed errorCode=$errorCode")
        }
    }

    private fun tryDial(dev: BluetoothDevice, pre: ByteArray?) {
        val addr = dev.address
        if (inFlight.size >= MAX_DIALS || addr in inFlight) return
        if (pre != null && haveLinkToPrefix(pre)) return // R4 (advert carries the mfg prefix)
        // Address-based suppression: peers whose advert has NO mfg prefix (pre=null — the macOS/iOS
        // peripherals) skip the prefix gate and were re-dialed on EVERY advert just to cancel after the
        // PSM read (the redial storm: ~99% wasted GATT connects, a battery/radio killer on BLE-only
        // nodes). Once we've resolved this MAC's peerId, suppress re-dials while we hold that link.
        addrToPeerId[addr]?.let { if (haveLinkTo(it)) return }
        val bkey = pre?.toHex() ?: addr
        if (System.currentTimeMillis() < (backoff[bkey] ?: 0L)) return // R2
        addrToBkey[addr] = bkey
        inFlight += addr
        Log.i(TAG, "DIALING addr=$addr prefix=${pre?.toHex()} inFlight=${inFlight.size}")
        val g = dev.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE) // MAIN thread, autoConnect=false
        gattByAddr[addr] = g
        main.postDelayed({
            if (addr in inFlight) {
                // A stuck dial is most often service-discovery that never completes: an iOS peer
                // rotated its random MAC or re-published its GATT server, and Android's cached
                // (now-empty) service list makes discoverServices() return nothing forever — the
                // 6-minute pixel→xr wedge. refresh() drops that cache so the next dial re-reads.
                Log.w(TAG, "DIAL TIMEOUT addr=$addr → refresh cache + closing GATT")
                refreshGattCache(g)
                g.close(); gattByAddr.remove(addr); fail(addr)
            }
        }, DIAL_TIMEOUT_MS) // R6
    }

    /// Clear Android's cached GATT service list for this connection via the hidden
    /// `BluetoothGatt.refresh()` (reflection). Best-effort: invalidates a stale/empty cache so the
    /// next `discoverServices()` against the same device re-reads its services from scratch.
    private fun refreshGattCache(g: BluetoothGatt) {
        runCatching { g.javaClass.getMethod("refresh").invoke(g) }
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            val addr = g.device.address
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "GATT connected addr=$addr → discoverServices")
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                g.discoverServices()
            } else {
                Log.i(TAG, "GATT state addr=$addr status=$status newState=$newState → close()")
                g.close(); gattByAddr.remove(addr); fail(addr) // ALWAYS close (§7.2)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val ch = g.getService(SERVICE_UUID.uuid)?.getCharacteristic(ENDPOINT_CHAR)
            if (ch != null) {
                Log.i(TAG, "GATT services discovered addr=${g.device.address} → readCharacteristic")
                g.readCharacteristic(ch)
            } else {
                Log.w(TAG, "GATT service/char missing addr=${g.device.address} → close()")
                g.close(); gattByAddr.remove(g.device.address); fail(g.device.address)
            }
        }

        // R1: API 33+ delivers the 4-arg form...
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) = handleRead(g, value, status)

        // R1: ...but API 29-32 (the entire sub-33 field) delivers ONLY this deprecated 3-arg form.
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            status: Int,
        ) {
            @Suppress("DEPRECATION")
            handleRead(g, ch.value ?: ByteArray(0), status)
        }
    }

    private fun handleRead(g: BluetoothGatt, value: ByteArray, status: Int) {
        val addr = g.device.address
        if (status != BluetoothGatt.GATT_SUCCESS || value.size < 18) {
            Log.w(TAG, "GATT read bad status=$status size=${value.size} addr=$addr → close()")
            g.close(); gattByAddr.remove(addr); fail(addr); return
        }
        val peerId = value.copyOfRange(2, 18)
        addrToPeerId[addr] = peerId // remember MAC→peerId so future prefix-less adverts from this MAC are dial-suppressed while linked
        addrToBkey[addr] = peerId.copyOfRange(0, 6).toHex() // R2: promote to stable nodeId prefix
        if (haveLinkTo(peerId)) { // R4: already linked → no redundant CoC
            Log.i(TAG, "GATT read: already linked to ${peerId.toHex().take(8)} → cancel dial")
            g.close(); gattByAddr.remove(addr); inFlight -= addr; return
        }
        val psm = ((value[0].toInt() and 0xff) shl 8) or (value[1].toInt() and 0xff)
        Log.i(TAG, "READ psm=$psm peer=${peerId.toHex().take(8)} addr=$addr → openL2CAP")
        val dev = g.device
        thread(name = "l2cap-dial") {
            try {
                val sock = dev.createInsecureL2capChannel(psm)
                sock.connect()
                Log.i(TAG, "L2CAP dialed psm=$psm addr=$addr — wrapping link")
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
                inFlight -= addr
                backoff.remove(addrToBkey[addr] ?: addr)
                val link = Link(
                    sock,
                    mintLinkId(),
                    isDialer = true,
                    myId,
                    onLink,
                    onData,
                    onClose = { l -> dialerLinkClosed(g, addr, l); onClose(l) },
                )
                if (CLOSE_GATT_AFTER_L2CAP) { g.close(); gattByAddr.remove(addr) } // R5 (flagged, default off)
                link.start()
            } catch (e: IOException) {
                Log.w(TAG, "L2CAP dial failed psm=$psm addr=$addr: ${e.message} → close()")
                g.close(); gattByAddr.remove(addr); fail(addr)
            }
        }
    }

    private fun dialerLinkClosed(g: BluetoothGatt, addr: String, l: Link) {
        if (!CLOSE_GATT_AFTER_L2CAP) {
            try { g.close() } catch (_: Exception) {}
            gattByAddr.remove(addr)
        }
        inFlight -= addr
        if (l.stableUp()) backoff.remove(addrToBkey[addr] ?: addr) // §6 reset after long-lived link
    }

    private fun fail(addr: String) {
        inFlight -= addr
        val key = addrToBkey[addr] ?: addr
        val remaining = (backoff[key] ?: 0L) - System.currentTimeMillis()
        val base = remaining.coerceAtLeast(500L)
        backoff[key] = System.currentTimeMillis() + minOf(base * 2, 30_000L) + (0..1000L).random()
        evictBackoff() // R2: TTL bound
    }

    private fun evictBackoff() {
        val now = System.currentTimeMillis()
        backoff.entries.removeAll { it.value < now - LOST_MS }
    }
}

// ---- BleBearer: owns myId, both planes, the dedup map (§2.3) + the linkId map, scan-mode downshift
//      (R9), STATUS timer, power-off teardown (R11). Runs the symmetric dual role. SPEC §9. ----
//
// The Android mirror of apple/HopBearers' BleBearer. THREADING: link callbacks (onUp/onData/onClose),
// the haveLinkTo* probes, send routing, and STATUS all run on MULTIPLE threads (per-link l2cap-rx/dial
// threads, the main Handler, the accept thread). Every map mutation/read is guarded by `lock`.
class BleBearer(private val ctx: Context, private val myId: ByteArray) : Bearer {
    /// Where links surface. Set by the consumer (or a BearerManager) before `start()`.
    override var sink: LinkSink? = null
    /// Short transport tag for the consumer's UI (Bearer contract). BLE links surface as "BT".
    override val transportName = "BT"

    private val lock = Any()
    private val linksByPeerId = HashMap<String, Link>()   // dedup: one survivor per peer (SPEC §2.3)
    private val linksByLinkId = HashMap<Long, Link>()      // send routing + linkUp/linkDown pairing
    private var nextLinkId = 1L                            // monotonic LinkId, minted per established Link

    private var central: Central? = null
    private var peripheral: Peripheral? = null
    private var statusExec: ScheduledExecutorService? = null

    // DIAG toggles via `adb shell setprop`:
    //   debug.blelab.noscan 1  → peripheral-only (don't scan/dial) — isolates scan-vs-peripheral starvation
    private val noScan = sysProp("debug.blelab.noscan") == "1"

    override fun start() {
        Log.i(TAG, "NODE START myId=${myId.toHex()} — ${if (noScan) "PERIPHERAL-ONLY (noscan)" else "symmetric dual role (peripheral + central)"}")
        peripheral = Peripheral(
            ctx, myId,
            mintLinkId = { mint() },
            onLink = { onUp(it) },
            onData = { l, b -> onData(l, b) },
            onClose = { onClose(it) },
        )
        central = Central(
            ctx, myId,
            mintLinkId = { mint() },
            onLink = { onUp(it) },
            onData = { l, b -> onData(l, b) },
            onClose = { onClose(it) },
            haveLinkTo = { synchronized(lock) { linksByPeerId.containsKey(it.toHex()) } },
            haveLinkToPrefix = { pre ->
                synchronized(lock) {
                    val h = pre.toHex(); linksByPeerId.keys.any { it.startsWith(h) }
                }
            },
        )
        peripheral?.start()
        if (!noScan) central?.start() else Log.i(TAG, "central scan/dial SUPPRESSED (debug.blelab.noscan=1)")
        val ex = Executors.newSingleThreadScheduledExecutor()
        ex.scheduleAtFixedRate(
            { synchronized(lock) { Log.i(TAG, "STATUS links=${linksByPeerId.size}") } },
            5, 5, TimeUnit.SECONDS,
        )
        statusExec = ex
    }

    override fun stop() { // SPEC R11: STATE_OFF / teardown
        statusExec?.shutdownNow(); statusExec = null
        closeAll()
        central?.stop(); central = null
        peripheral?.stop(); peripheral = null
    }

    override fun send(bytes: ByteArray, link: LinkId) {
        val l = synchronized(lock) { linksByLinkId[link] } // no-op if link closed/unknown
        l?.sendData(bytes)
    }

    private fun mint(): Long = synchronized(lock) { val id = nextLinkId; nextLinkId += 1; id }

    private fun onUp(link: Link) { // HELLO completed: surface to sink, then dedup (§2.3)
        val peer = link.peerId ?: return
        val key = peer.toHex()
        synchronized(lock) { linksByLinkId[link.linkId] = link } // register for send routing + down pairing
        // Surface BEFORE dedup (Apple parity): both legs of a duplicate pair come up, then dedup closes
        // the loser → the consumer sees that loser's linkDown.
        sink?.linkUp(link.linkId, if (link.isDialer) HopRole.DIALER else HopRole.ACCEPTOR, peer)
        var drop: Link? = null
        synchronized(lock) {
            val existing = linksByPeerId[key]
            if (existing == null || existing === link) {
                linksByPeerId[key] = link
            } else {
                val keepDialed = nodeIdGreater(myId, peer) // keep MY dialed channel iff I'm the greater id
                val keep = listOf(existing, link).firstOrNull { it.isDialer == keepDialed } ?: link
                drop = if (keep === link) existing else link
                linksByPeerId[key] = keep // R3: set survivor BEFORE closing the dropped channel
                Log.i(TAG, "DEDUP kept isDialer=${keep.isDialer} peer=${key.take(8)}")
            }
        }
        drop?.close("dedup") // outside lock: close → onClose → sink.linkDown for the loser
        updateScan()
    }

    private fun onData(link: Link, bytes: ByteArray) {
        sink?.linkBytes(link.linkId, bytes) // one DATA frame → consumer
    }

    private fun onClose(link: Link) { // R3: identity-checked removal
        val peer = link.peerId
        var removedPeer = false
        val wasUp: Boolean
        synchronized(lock) {
            wasUp = linksByLinkId.remove(link.linkId) != null // true iff linkUp had fired
            if (peer != null) {
                val key = peer.toHex()
                if (linksByPeerId[key] === link) { linksByPeerId.remove(key); removedPeer = true }
            }
        }
        if (wasUp) sink?.linkDown(link.linkId) // pair every linkDown with a prior linkUp
        if (removedPeer) updateScan()
    }

    fun closeAll() { // SPEC R11: drop all local links on power-off / stop
        val all = synchronized(lock) { linksByPeerId.values.toList() }
        all.forEach { it.close("power-off") }
    }

    private fun updateScan() {
        if (noScan) return
        val empty = synchronized(lock) { linksByPeerId.isEmpty() }
        if (empty) {
            central?.requestScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY, 2000)
        } else {
            central?.requestScanMode(ScanSettings.SCAN_MODE_BALANCED, 10_000) // R9: 10 s downshift hysteresis
        }
    }
}

internal fun sysProp(key: String): String = try {
    @Suppress("UNCHECKED_CAST")
    val m = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
    (m.invoke(null, key) as? String) ?: ""
} catch (_: Throwable) { "" }
