package sh.hopme.bearers.relay

// RelayBackoff: the relay reconnect schedule as pure, Android-free logic so it is unit-testable on a
// plain JVM (RelayBearer.kt pulls in android.util.Log + OkHttp, which don't load under a stubbed
// android.jar in a testDebugUnitTest). The state machine that CALLS this (dial/onFailure/scheduleReconnect)
// stays in RelayBearer; this file owns only the arithmetic the tests need to pin.
//
// The rules being locked in:
//   - Exponential backoff from RELAY_BACKOFF_MIN_MS, doubling each failed dial, capped at
//     RELAY_BACKOFF_MAX_MS (F-13).
//   - A server-driven 429 Retry-After overrides the schedule for exactly one reconnect (F-13).
//   - After RELAY_DEAD_AFTER consecutive failed dials (the fleet is torn down, not a blip) hold at the
//     multi-minute RELAY_BACKOFF_DEAD_MS ceiling so we stop waking the radio every ~30s (android-09).
//   - A link that stays up past RELAY_STABLE_MS resets BOTH the delay and the dead streak.

internal const val RELAY_BACKOFF_MIN_MS = 1_000L
internal const val RELAY_BACKOFF_MAX_MS = 30_000L
internal const val RELAY_STABLE_MS = 20_000L        // F-13: only reset backoff after the link holds this long
internal const val RELAY_DEAD_AFTER = 8             // consecutive failed dials before the long ceiling kicks in
internal const val RELAY_BACKOFF_DEAD_MS = 300_000L // ~5 min ceiling for a clearly-dead endpoint

/// The result of one reconnect-schedule step: how long to wait, and the carried-forward backoff base
/// for the next step. Immutable so the state machine can thread it without shared mutation in a test.
internal data class RelayStep(val delayMs: Long, val nextBackoffMs: Long)

internal object RelayBackoff {
    /// Compute the delay before the next dial attempt.
    ///
    /// @param deadStreak consecutive failed dials INCLUDING this one (>= 1).
    /// @param backoffMs  the current exponential base (starts at RELAY_BACKOFF_MIN_MS).
    /// @param retryAfterMs a one-shot server-driven Retry-After (429) in ms, or null.
    /// @return the delay to schedule + the backoff base to carry into the next step.
    fun step(deadStreak: Int, backoffMs: Long, retryAfterMs: Long?): RelayStep {
        if (retryAfterMs != null) {
            // Honor the server's Retry-After for exactly this reconnect; don't advance the exponential
            // base (matches RelayBearer.scheduleReconnect, which nulls retryAfterMs and returns).
            return RelayStep(retryAfterMs, backoffMs)
        }
        if (deadStreak >= RELAY_DEAD_AFTER) {
            // Endpoint dead for many attempts: hold at the multi-minute ceiling; pin the base at the cap.
            return RelayStep(RELAY_BACKOFF_DEAD_MS, RELAY_BACKOFF_MAX_MS)
        }
        // Normal path: use the current base, then double it (capped).
        val next = (backoffMs * 2).coerceAtMost(RELAY_BACKOFF_MAX_MS)
        return RelayStep(backoffMs, next)
    }

    /// A 429 upgrade response yields this Retry-After (ms): the header's seconds if present/parseable,
    /// else the normal max cap. Mirrors RelayBearer.onFailure.
    fun retryAfterFrom429(headerSeconds: Long?): Long =
        headerSeconds?.times(1000) ?: RELAY_BACKOFF_MAX_MS
}
