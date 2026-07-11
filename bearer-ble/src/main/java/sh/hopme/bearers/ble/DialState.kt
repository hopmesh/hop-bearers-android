package sh.hopme.bearers.ble

import sh.hop.toHex

// DialState - the Central's per-target dial bookkeeping + gating, lifted out of Central (BleBearer.kt)
// into a pure, Android-free object so the historically defect-prone dial-slot / backoff / suppression
// logic is unit-testable on a plain JVM. Same split as DialBackoff.kt / BleDedup.kt: Central keeps the
// device-bound radio bits (connectGatt, the GATT callbacks, the l2cap-dial thread, the scan throttle,
// the Handler timeouts, the live BluetoothGatt map), and delegates every dial-map decision here.
//
// android-04: these maps were read/mutated from three thread contexts and a HashMap under concurrent
// mutation corrupts (a MAC stuck in inFlight silently wedges all future dials to that device). That
// concurrency is owned by Central's single `dial` lock, which wraps every call into this object, so the
// methods here are deliberately NOT self-synchronized - Central provides the mutual exclusion, exactly
// as before, and a single-threaded unit test needs none. BEHAVIOR-PRESERVING: every decision (the
// MAX_DIALS slot gate, the already-linked suppression, the R2 backoff window, the R7 count-based backoff
// growth, the LOST_MS eviction, the prefix promotion, the R4 one-wait-per-MAC) is byte-for-byte the old
// inline logic.
internal class DialState(
    private val maxDials: Int = MAX_DIALS,
    private val lostMs: Long = LOST_MS,
    private val backoffFor: (Int, Long) -> Long = ::nextBackoffMs,
) {
    private val inFlight = mutableSetOf<String>()          // R2: short-lived, MAC-keyed
    private val backoff = mutableMapOf<String, Long>()     // R2: prefix-hex (stable) or MAC → not-before ms
    private val failCount = mutableMapOf<String, Int>()    // R7: consecutive dial failures per backoff key
    private val addrToBkey = HashMap<String, String>()     // MAC → backoff key (prefix once known)
    private val addrToPeerId = HashMap<String, ByteArray>() // MAC → resolved peerId (prefix-less suppression)
    private val pendingWaits = mutableSetOf<String>()      // R4: one deferred wait per MAC

    /// android-04: decide + claim a dial slot atomically (Central holds `dial` across this). Returns true
    /// and records the claim iff a dial to [addr] may proceed: a free slot, not already in flight, not
    /// already linked to this MAC's resolved peer, and past any backoff window for [bkey].
    fun tryClaim(addr: String, bkey: String, now: Long, haveLinkTo: (ByteArray) -> Boolean): Boolean {
        if (inFlight.size >= maxDials || addr in inFlight) return false
        addrToPeerId[addr]?.let { if (haveLinkTo(it)) return false }
        if (now < (backoff[bkey] ?: 0L)) return false // R2
        addrToBkey[addr] = bkey
        inFlight += addr
        return true
    }

    /// handleRead: remember MAC→peerId (so future prefix-less adverts are suppressed while linked) and
    /// promote the backoff key from the MAC to the stable 6-byte nodeId prefix (R2).
    fun promote(addr: String, peerId: ByteArray) {
        addrToPeerId[addr] = peerId
        addrToBkey[addr] = peerId.copyOfRange(0, 6).toHex()
    }

    /// The resolved peerId for a MAC (null until its GATT read completes).
    fun peerIdFor(addr: String): ByteArray? = addrToPeerId[addr]

    /// The (stable-if-known) backoff key for a MAC, defaulting to the MAC before its prefix is resolved.
    fun bkeyOf(addr: String): String = addrToBkey[addr] ?: addr

    /// Free the dial slot for a MAC without touching its backoff (a completed/half-completed dial).
    fun release(addr: String) { inFlight -= addr }

    /// R7: a failed dial to [addr] - free the slot and grow the backoff by CONSECUTIVE failure count
    /// (not wall-clock delta, which never grew), then evict aged-out entries.
    fun fail(addr: String, now: Long, jitter: Long) {
        inFlight -= addr
        val key = addrToBkey[addr] ?: addr
        val n = (failCount[key] ?: 0) + 1
        failCount[key] = n
        backoff[key] = now + backoffFor(n, jitter)
        evictBackoff(now)
    }

    /// A dial reached a real, reachable Hop peer (link UP / already-linked): reset the failure state for
    /// this MAC's key so a later transient hiccup starts backoff from the floor, not the quarantine.
    fun succeededForAddr(addr: String) {
        val key = bkeyOf(addr)
        failCount.remove(key)
        backoff.remove(key)
    }

    /// §6: a link that stayed up long enough clears its backoff window (but keeps the slot bookkeeping).
    fun clearBackoffForAddr(addr: String) { backoff.remove(bkeyOf(addr)) }

    /// R4: claim the one deferred wait allowed per MAC (true iff newly added).
    fun addWait(addr: String): Boolean = pendingWaits.add(addr)
    fun removeWait(addr: String) { pendingWaits.remove(addr) }

    /// stop(): drop all in-flight claims (Central closes the live GATTs separately).
    fun clearInFlight() { inFlight.clear() }

    private fun evictBackoff(now: Long) {
        backoff.entries.removeAll { it.value < now - lostMs }
        // R7: forget the failure count once its backoff has aged out, so a peer seen again much later
        // starts fresh from the floor rather than inheriting a stale quarantine.
        failCount.keys.retainAll(backoff.keys)
    }

    // ---- inspectors (unit tests) -----------------------------------------------------------------
    fun inFlightCount(): Int = inFlight.size
    fun inFlightContains(addr: String): Boolean = addr in inFlight
    fun backoffUntil(bkey: String): Long? = backoff[bkey]
    fun failCountOf(bkey: String): Int = failCount[bkey] ?: 0
    fun isWaiting(addr: String): Boolean = addr in pendingWaits
}
