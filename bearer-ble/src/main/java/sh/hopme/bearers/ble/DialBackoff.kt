package sh.hopme.bearers.ble

// R7 dial backoff — deliberately in its own Android-free file so the schedule is unit-testable (the
// rest of BleBearer.kt initializes Android-typed top-level vals like ParcelUuid, whose file facade
// class can't load under a stubbed android.jar in a JVM unit test).
//
// The base doubles per CONSECUTIVE failure (not per wall-clock delta): a stuck dial takes
// DIAL_TIMEOUT_MS (12s) to fail, which always outlasts a sub-2s backoff, so the old delta-based
// growth reset to the floor every cycle. A peer that GATT-connects but never yields an L2CAP channel
// (a rotated MAC / non-Hop advertiser) then re-dialed every ~13s forever, monopolizing a dial slot
// and starving healthy peers. Count-based growth quarantines such a target progressively instead.
internal const val BACKOFF_BASE_MS = 2_000L
internal const val BACKOFF_MAX_MS = 30_000L          // normal cap
internal const val BACKOFF_QUARANTINE_AFTER = 6      // consecutive failures before the long cap kicks in
internal const val BACKOFF_QUARANTINE_MS = 120_000L  // a chronically-failing target (clearly not a reachable peer) backs off ~2 min

/// Pure, deterministic backoff duration for the Nth CONSECUTIVE failed dial to one target (N ≥ 1),
/// plus a caller-supplied jitter. Exponential in N so growth can't be defeated by a dial timeout that
/// outlasts the previous window: 2s, 4s, 8s, 16s, 30s(cap)… then a ~2min quarantine past
/// [BACKOFF_QUARANTINE_AFTER].
internal fun nextBackoffMs(failCount: Int, jitter: Long): Long {
    val n = failCount.coerceAtLeast(1)
    val exp = BACKOFF_BASE_MS shl (n - 1).coerceIn(0, 20) // guard the shift; 2s << 20 already dwarfs any cap
    val cap = if (n >= BACKOFF_QUARANTINE_AFTER) BACKOFF_QUARANTINE_MS else BACKOFF_MAX_MS
    return minOf(exp, cap) + jitter
}
