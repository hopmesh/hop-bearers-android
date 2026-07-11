package sh.hopme.bearers.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hop.toHex

/**
 * cov/android-bearers: the pure Central dial bookkeeping + gating (DialState), lifted out of the
 * device-bound Central so the historically defect-prone dial-slot / backoff / suppression logic is
 * unit-testable on a plain JVM. These pin: the MAX_DIALS slot gate + in-flight dedup (the android-04
 * one-MAC-wedge class of bug), the already-linked suppression, the R2 backoff window, the R7 count-based
 * backoff growth + LOST_MS eviction, the prefix promotion, and the R4 one-wait-per-MAC.
 */
class DialStateTest {

    private val never: (ByteArray) -> Boolean = { false }
    private val always: (ByteArray) -> Boolean = { true }
    private fun peer(v: Int) = ByteArray(16) { v.toByte() }

    // ---- slot gate + in-flight dedup (android-04) -------------------------------------------------

    @Test fun claimsUpToMaxDialsThenGates() {
        val d = DialState()
        assertTrue(d.tryClaim("AA", "AA", now = 0, haveLinkTo = never))
        assertTrue(d.tryClaim("BB", "BB", now = 0, haveLinkTo = never))
        assertEquals(MAX_DIALS, d.inFlightCount())
        // A third distinct target is gated: only MAX_DIALS concurrent dials.
        assertFalse("MAX_DIALS slots are full", d.tryClaim("CC", "CC", now = 0, haveLinkTo = never))
    }

    @Test fun sameAddressIsNotClaimedTwice() {
        val d = DialState()
        assertTrue(d.tryClaim("AA", "AA", 0, never))
        assertFalse("an in-flight MAC can't be claimed again", d.tryClaim("AA", "AA", 0, never))
        assertEquals(1, d.inFlightCount())
    }

    @Test fun releaseFreesTheSlot() {
        val d = DialState()
        d.tryClaim("AA", "AA", 0, never)
        d.release("AA")
        assertEquals(0, d.inFlightCount())
        assertTrue("a freed slot can be reclaimed", d.tryClaim("AA", "AA", 0, never))
    }

    @Test fun clearInFlightDropsAllClaims() {
        val d = DialState()
        d.tryClaim("AA", "AA", 0, never); d.tryClaim("BB", "BB", 0, never)
        d.clearInFlight()
        assertEquals(0, d.inFlightCount())
    }

    // ---- already-linked suppression (R4) ---------------------------------------------------------

    @Test fun alreadyLinkedPeerIsSuppressed() {
        val d = DialState()
        val p = peer(0x42)
        d.promote("AA", p) // resolves MAC AA -> peerId p
        d.release("AA")
        // With a live link to p, a re-dial to AA is suppressed by the addrToPeerId gate.
        assertFalse("suppressed while linked to this MAC's peer", d.tryClaim("AA", p.copyOfRange(0, 6).toHex(), 0, always))
        // Once the link drops (haveLinkTo=false), the same target may be dialed again.
        assertTrue(d.tryClaim("AA", p.copyOfRange(0, 6).toHex(), 0, never))
    }

    // ---- R2 backoff window + R7 count-based growth ------------------------------------------------

    @Test fun backoffWindowBlocksUntilItExpires() {
        val d = DialState()
        d.tryClaim("AA", "key", 0, never)
        d.fail("AA", now = 1_000, jitter = 0) // sets backoff[key] = 1_000 + nextBackoffMs(1,0) = 3_000
        val until = d.backoffUntil("key")!!
        assertEquals(1, d.failCountOf("key"))
        assertFalse("still inside the backoff window", d.tryClaim("AA", "key", until - 1, never))
        assertTrue("past the backoff window", d.tryClaim("AA", "key", until, never))
    }

    @Test fun consecutiveFailuresGrowTheBackoff() {
        val d = DialState()
        // Model repeated failures on one key; each grows the count-based backoff (2s,4s,8s...).
        var prevDelay = 0L
        for (n in 1..5) {
            d.release("AA")
            d.fail("AA", now = 0, jitter = 0) // fail keys off addrToBkey; here bkey defaults to MAC "AA"
            val until = d.backoffUntil("AA")!!
            assertTrue("backoff never shrinks (n=$n)", until >= prevDelay)
            prevDelay = until
            assertEquals(n, d.failCountOf("AA"))
        }
    }

    @Test fun promoteMovesBackoffKeyToTheStablePrefix() {
        val d = DialState()
        val p = peer(0x7)
        d.tryClaim("AA", "AA", 0, never)
        d.promote("AA", p) // bkey MAC -> 6-byte prefix
        assertEquals(p.copyOfRange(0, 6).toHex(), d.bkeyOf("AA"))
        assertArrayEqualsSafe(p, d.peerIdFor("AA"))
    }

    @Test fun succeededClearsFailureStateForTheAddr() {
        val d = DialState()
        d.tryClaim("AA", "AA", 0, never)
        d.fail("AA", now = 0, jitter = 0)
        assertTrue(d.failCountOf("AA") > 0)
        d.succeededForAddr("AA")
        assertEquals("a reachable peer clears its failure count", 0, d.failCountOf("AA"))
        assertNull("and its backoff window", d.backoffUntil("AA"))
    }

    @Test fun stableLinkClearsBackoffWindow() {
        val d = DialState()
        d.tryClaim("AA", "AA", 0, never)
        d.fail("AA", now = 0, jitter = 0)
        assertNotNull(d.backoffUntil("AA"))
        d.clearBackoffForAddr("AA")
        assertNull("a long-lived link clears its backoff", d.backoffUntil("AA"))
    }

    @Test fun agedOutBackoffIsEvictedAndFailCountForgotten() {
        val d = DialState()
        d.tryClaim("AA", "AA", now = 0, haveLinkTo = never)
        d.fail("AA", now = 0, jitter = 0) // backoff[AA] ~= 3_000
        // A much-later failure on a DIFFERENT key evicts AA's aged-out window (value < now - LOST_MS).
        d.fail("BB", now = 10_000_000, jitter = 0)
        assertNull("aged-out backoff is evicted", d.backoffUntil("AA"))
        assertEquals("and its stale fail count is forgotten", 0, d.failCountOf("AA"))
    }

    // ---- R4 one-wait-per-MAC ----------------------------------------------------------------------

    @Test fun oneWaitPerMac() {
        val d = DialState()
        assertTrue("first wait is claimed", d.addWait("AA"))
        assertFalse("a second concurrent wait is refused", d.addWait("AA"))
        assertTrue(d.isWaiting("AA"))
        d.removeWait("AA")
        assertFalse(d.isWaiting("AA"))
        assertTrue("after removal a fresh wait is allowed", d.addWait("AA"))
    }

    @Test fun peerIdForIsNullBeforeResolve() {
        val d = DialState()
        assertNull(d.peerIdFor("AA"))
        assertEquals("bkey defaults to the MAC before resolve", "AA", d.bkeyOf("AA"))
    }

    // small local asserts (avoid extra imports)
    private fun assertNotNull(v: Any?) = assertTrue("expected non-null", v != null)
    private fun assertArrayEqualsSafe(a: ByteArray, b: ByteArray?) =
        assertTrue("arrays equal", b != null && a.contentEquals(b))
}
