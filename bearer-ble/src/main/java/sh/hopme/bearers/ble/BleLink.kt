package sh.hopme.bearers.ble

// PLAT-001: the slice of a live BLE link the bearer's tracking / dedup / routing actually needs, with
// NO Android types in it. `Link` (BleBearer.kt) is the only production implementation; it wraps a real
// BluetoothSocket, so before this seam existed the bearer's own lifecycle logic could not be driven in
// a JVM test at all and the stop-race below had no way to be pinned.
//
// This mirrors the Apple bearer's `DedupLink` protocol, which exists for exactly the same reason. The
// two BLE bearers have drifted twice; keeping the seams the same shape is how a fix on one side stays
// reviewable against the other.
internal interface BleLink {
    val linkId: Long
    val peerId: ByteArray?
    val isDialer: Boolean
    fun sendData(bytes: ByteArray)
    fun close(why: String)
}
