package sh.hopme.bearers.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * quality-cov: the two remaining pure seams the wire tests didn't reach — LanFrame's content-based
 * equals/hashCode (it carries a mutable ByteArray, so it hand-rolls them) and LanDedup's DEGENERATE
 * fallbacks (both competing links share a role, or neither matches the keep rule). The degenerate
 * branches shouldn't happen for a real dialer/acceptor pair, but they must still resolve
 * deterministically (never throw, never leave both pipes) so a weird symmetric discovery can't wedge.
 */
class LanFrameDedupTest {

    // ---- LanFrame value semantics ------------------------------------------------------

    @Test fun lanFrameEqualsIsContentBased() {
        val a = LanFrame(L_DATA, byteArrayOf(1, 2, 3))
        val b = LanFrame(L_DATA, byteArrayOf(1, 2, 3))   // distinct array, same bytes
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test fun lanFrameDiffersOnTypeOrPayload() {
        val base = LanFrame(L_DATA, byteArrayOf(1, 2, 3))
        assertNotEquals(base, LanFrame(L_PING, byteArrayOf(1, 2, 3)))   // different type
        assertNotEquals(base, LanFrame(L_DATA, byteArrayOf(1, 2)))      // different payload
        assertFalse(base.equals("not a frame"))
        assertTrue(base.equals(base))
    }

    // ---- LanDedup degenerate fallbacks (the two branches wire tests skip) ---------------

    @Test fun bothLinksShareTheKeptRoleFallsBackToExisting() {
        // amGreater => keepDialed=true; if BOTH competing links are dialers (degenerate), keep existing.
        assertEquals(DedupKeep.EXISTING, LanDedup.decide(amGreater = true, existingIsDialer = true, incomingIsDialer = true))
        // amGreater=false => keepDialed=false; if BOTH are acceptors, keep existing.
        assertEquals(DedupKeep.EXISTING, LanDedup.decide(amGreater = false, existingIsDialer = false, incomingIsDialer = false))
    }

    @Test fun neitherLinkMatchesTheKeptRoleFallsBackToIncoming() {
        // amGreater => keepDialed=true; if NEITHER is a dialer, fall back to incoming (matches onUp's ?: link).
        assertEquals(DedupKeep.INCOMING, LanDedup.decide(amGreater = true, existingIsDialer = false, incomingIsDialer = false))
        // amGreater=false => keepDialed=false; if NEITHER is an acceptor (both dialers), fall back to incoming.
        assertEquals(DedupKeep.INCOMING, LanDedup.decide(amGreater = false, existingIsDialer = true, incomingIsDialer = true))
    }
}
