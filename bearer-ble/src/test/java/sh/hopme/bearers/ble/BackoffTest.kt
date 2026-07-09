package sh.hopme.bearers.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R7 regression: the dial backoff must GROW with consecutive failures. The old delta-based scheme
 * reset to the floor every cycle because a 12s dial timeout always outlasts a sub-2s backoff, so a
 * peer that GATT-connects but never yields an L2CAP channel re-dialed every ~13s forever, starving
 * healthy peers. These lock in the count-based growth + caps.
 */
class BackoffTest {
    // Use zero jitter so the schedule is exact; jitter is exercised separately.
    private fun b(n: Int) = nextBackoffMs(n, 0L)

    @Test fun growsExponentiallyThenCaps() {
        assertEquals(2_000L, b(1))   // 2s
        assertEquals(4_000L, b(2))   // 4s
        assertEquals(8_000L, b(3))   // 8s
        assertEquals(16_000L, b(4))  // 16s
        assertEquals(BACKOFF_MAX_MS, b(5)) // 32s clamped to the 30s normal cap
    }

    @Test fun quarantinesAChronicFailure() {
        // Past the quarantine threshold the cap lifts to ~2 min so a clearly-unreachable target
        // (rotated MAC / non-Hop advertiser) stops monopolizing a dial slot every cycle.
        assertTrue("n=6 must exceed the normal cap", b(6) > BACKOFF_MAX_MS)
        assertEquals(BACKOFF_QUARANTINE_MS, b(20)) // deep failure count pinned at the quarantine cap
    }

    @Test fun isMonotonicNonDecreasing() {
        var prev = 0L
        for (n in 1..30) {
            val cur = b(n)
            assertTrue("backoff must never shrink as failures accumulate (n=$n: $cur < $prev)", cur >= prev)
            prev = cur
        }
    }

    @Test fun jitterIsAddedWithinBound() {
        // Jitter only ever adds [0, jitter]; the floor stays the deterministic schedule.
        assertEquals(2_000L, nextBackoffMs(1, 0L))
        assertEquals(2_500L, nextBackoffMs(1, 500L))
        assertTrue(nextBackoffMs(1, 1_000L) in 2_000L..3_000L)
    }

    @Test fun clampsFloorFailureCount() {
        // Defensive: N < 1 is treated as the first failure, never a shift-underflow or negative delay.
        assertEquals(b(1), nextBackoffMs(0, 0L))
        assertEquals(b(1), nextBackoffMs(-5, 0L))
    }
}
