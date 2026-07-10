package sh.hopme.bearers.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * quality-net-03: the relay reconnect/backoff schedule.
 *
 * These lock in: exponential growth to the 30s cap, a one-shot 429 Retry-After override, and the
 * android-09 "dead endpoint" ceiling (after RELAY_DEAD_AFTER consecutive failed dials, hold at the
 * multi-minute ceiling so a torn-down fleet stops waking the radio every ~30s). Regressions here
 * previously either hammered a dead relay every 5s or reset to the 1s floor on a flapping link.
 */
class RelayBackoffTest {

    /// Drive the reconnect loop the way RelayBearer does: increment the streak, step, carry the base.
    /// Returns the per-attempt scheduled delays (base, no jitter; jitter is [0,1s) added separately).
    private fun run(attempts: Int, retryAfterAt: Map<Int, Long> = emptyMap()): List<Long> {
        var backoff = RELAY_BACKOFF_MIN_MS
        var streak = 0
        val delays = ArrayList<Long>()
        for (a in 1..attempts) {
            streak++
            val step = RelayBackoff.step(streak, backoff, retryAfterAt[a])
            backoff = step.nextBackoffMs
            delays.add(step.delayMs)
        }
        return delays
    }

    @Test fun growsExponentiallyThenCapsAt30s() {
        // 1s, 2s, 4s, 8s, 16s, then clamped to the 30s cap.
        val d = run(7)
        assertEquals(1_000L, d[0])
        assertEquals(2_000L, d[1])
        assertEquals(4_000L, d[2])
        assertEquals(8_000L, d[3])
        assertEquals(16_000L, d[4])
        assertEquals(RELAY_BACKOFF_MAX_MS, d[5])   // 32s clamped to 30s
        assertEquals(RELAY_BACKOFF_MAX_MS, d[6])
    }

    @Test fun deadEndpointHoldsAtMultiMinuteCeiling() {
        // Once the streak reaches RELAY_DEAD_AFTER, the schedule stops the ~30s radio wakeups and holds
        // at the multi-minute dead ceiling (android-09).
        val d = run(RELAY_DEAD_AFTER + 3)
        for (i in 0 until RELAY_DEAD_AFTER - 1) {
            assertTrue("attempt ${i + 1} is a normal (<=cap) backoff", d[i] <= RELAY_BACKOFF_MAX_MS)
        }
        for (i in RELAY_DEAD_AFTER - 1 until d.size) {
            assertEquals("attempt ${i + 1} holds at the dead ceiling", RELAY_BACKOFF_DEAD_MS, d[i])
        }
    }

    @Test fun retryAfterOverridesForExactlyOneReconnect() {
        // A 429 Retry-After on attempt 3 uses the server value for that one reconnect WITHOUT advancing
        // the exponential base, so attempt 4 resumes from where attempt 2 left off (4s).
        val d = run(5, retryAfterAt = mapOf(3 to 45_000L))
        assertEquals(1_000L, d[0])
        assertEquals(2_000L, d[1])
        assertEquals(45_000L, d[2])              // server-driven override
        assertEquals(4_000L, d[3])               // base was NOT advanced by the override
        assertEquals(8_000L, d[4])
    }

    @Test fun retryAfterHeaderParsing() {
        // Header seconds -> ms.
        assertEquals(30_000L, RelayBackoff.retryAfterFrom429(30L))
        // Missing/unparseable header -> the normal cap.
        assertEquals(RELAY_BACKOFF_MAX_MS, RelayBackoff.retryAfterFrom429(null))
    }

    @Test fun stableLinkResetsBaseAndStreak() {
        // A live link past RELAY_STABLE_MS resets backoff to the floor and the dead streak to 0
        // (RelayBearer onOpen). Model that reset and confirm the next failure starts at 1s again, not
        // the dead ceiling.
        // Drive into the dead ceiling first.
        run(RELAY_DEAD_AFTER + 2)
        // Reset (what onOpen does): backoff=MIN, streak=0. Next failure is streak=1.
        val afterReset = RelayBackoff.step(1, RELAY_BACKOFF_MIN_MS, null)
        assertEquals(1_000L, afterReset.delayMs)
        assertEquals(2_000L, afterReset.nextBackoffMs)
    }

    @Test fun delaysAreNeverBelowTheFloor() {
        for (delay in run(20)) {
            assertTrue("no scheduled delay should be under the 1s floor", delay >= RELAY_BACKOFF_MIN_MS)
        }
    }
}
