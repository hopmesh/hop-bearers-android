package sh.hopme.bearers.ble

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * android-r2-07: the BLE one-pipe-per-peer dedup keep-rule (SPEC §2.3) had zero JVM coverage even
 * though it is the exact seam behind past securing-stuck / link-thrash bugs. When discovery is
 * symmetric, both a dialer and an acceptor channel to the same peer come up; exactly one survives, and
 * BOTH ends must independently choose the SAME survivor or they close opposite legs and thrash.
 *
 * These pin the invariant: the greater-nodeId side keeps ITS dialed channel; the lesser-id side keeps
 * the channel it ACCEPTED (which is the greater side's dialed one) — so the two ends agree.
 */
class BleDedupTest {

    @Test fun greaterIdKeepsItsDialedChannel() {
        // I'm the greater id. Two links to the peer: one I dialed, one I accepted. Keep the dialed one.
        val keep = BleDedup.decide(amGreater = true, existingIsDialer = true, incomingIsDialer = false)
        assertEquals(BleDedupKeep.EXISTING, keep)
        val keep2 = BleDedup.decide(amGreater = true, existingIsDialer = false, incomingIsDialer = true)
        assertEquals(BleDedupKeep.INCOMING, keep2)
    }

    @Test fun lesserIdKeepsItsAcceptedChannel() {
        // I'm the lesser id. Keep the channel I ACCEPTED (isDialer == false).
        val keep = BleDedup.decide(amGreater = false, existingIsDialer = false, incomingIsDialer = true)
        assertEquals(BleDedupKeep.EXISTING, keep)
        val keep2 = BleDedup.decide(amGreater = false, existingIsDialer = true, incomingIsDialer = false)
        assertEquals(BleDedupKeep.INCOMING, keep2)
    }

    @Test fun bothEndsAgreeOnTheSameSurvivor() {
        // The core correctness property: for the SAME physical pair, the greater end and the lesser end
        // must keep the SAME channel. Model a dialer/acceptor pair between A (greater) and B (lesser).
        //
        // From A's view (amGreater=true): its DIALED channel to B is the survivor.
        // From B's view (amGreater=false): its ACCEPTED channel from A is the survivor.
        // Those are the two ends of ONE physical channel (A dialed → B accepted), so both keep it.
        val aKeepsDialed = BleDedup.decide(amGreater = true, existingIsDialer = true, incomingIsDialer = false)
        val bKeepsAccepted = BleDedup.decide(amGreater = false, existingIsDialer = false, incomingIsDialer = true)
        assertEquals(BleDedupKeep.EXISTING, aKeepsDialed)   // A keeps A→B (its dialer)
        assertEquals(BleDedupKeep.EXISTING, bKeepsAccepted) // B keeps A→B (its acceptor) — SAME channel
    }

    @Test fun degenerateNoMatchFallsBackToIncoming() {
        // If neither competing link's role matches the keep rule (shouldn't happen for a real
        // dialer/acceptor pair), fall back to the incoming link — matching BleBearer.onUp's `?: link`.
        val keep = BleDedup.decide(amGreater = true, existingIsDialer = false, incomingIsDialer = false)
        assertEquals(BleDedupKeep.INCOMING, keep)
    }

    @Test fun degenerateBothMatchKeepsExisting() {
        // If BOTH roles match (also degenerate), keep existing — matching the old firstOrNull(existing-first).
        val keep = BleDedup.decide(amGreater = true, existingIsDialer = true, incomingIsDialer = true)
        assertEquals(BleDedupKeep.EXISTING, keep)
    }
}
