package sh.hopme.bearers.ble

// BleBeacon: the iBeacon (Layer C) relaunch-signal value + payload builder, in an Android-free file so
// the pure layout is unit-testable on a plain JVM (the rest of BleBearer.kt initializes Android-typed
// top-level vals like ParcelUuid, whose file facade class can't load under a stubbed android.jar in a
// testDebugUnitTest). Same split as DialBackoff.kt / BleDedup.kt. No logic change: only java.util.UUID
// and java.nio.ByteBuffer here, no Android types.

import java.util.UUID

// iBeacon (Layer C) — the iOS *relaunch* signal. Byte-matches iOS BeaconWake.swift BEACON_UUID.
// Public so the app driver references THIS single value instead of redefining the literal (F-40).
val BEACON_UUID: UUID = UUID.fromString("7ED7BEAC-3C2A-4F19-9B8E-1A2B3C4D5E6F") // == iOS BEACON_UUID

/// Build the 23-byte Apple-manufacturer-data iBeacon payload (subtype + 128-bit UUID + major/minor +
/// measured power), all big-endian. A single wrong byte means a force-quit iOS peer never wakes.
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
