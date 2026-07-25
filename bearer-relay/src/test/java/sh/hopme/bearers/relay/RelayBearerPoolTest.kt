package sh.hopme.bearers.relay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §19 relay pool: the URL must be resolved PER DIAL ATTEMPT, not fixed at construction.
 *
 * Why this matters. The shared `BearerManager` has no live re-register, so a bearer holding a fixed
 * URL retried a dead relay forever and the only way to move was an app restart. Asking the host on
 * every attempt lets the node's health-scored pool hand back a different endpoint, which is the whole
 * failover mechanism.
 *
 * DETERMINISTIC BY CONSTRUCTION. An earlier draft of this file started the bearer and waited for real
 * dials against `.invalid` hosts to fail; it passed locally and failed in CI, which is exactly the
 * flake bearers/CLAUDE.md warns about ("pin the ordering, do not sleep"). These tests drive the
 * resolution and reporting seams directly: no sockets, no sleeps, no wall-clock. The socket-level
 * behavior (a dead endpoint failing over to a live one on the next attempt) is covered end to end on
 * the Apple side against a loopback WS server, in
 * bearers/apple/HopBearerRelay/Tests/.../testPooledBearerResolvesPerAttemptAndReportsOutcomes.
 */
class RelayBearerPoolTest {

    @Test fun fixedUrlConstructionResolvesToThatUrlEveryTime() {
        // Backwards compatibility: the single-arg constructor must behave exactly as before, so
        // tests, the ble-lab client, and pinned deployments are unaffected.
        val b = RelayBearer("wss://only.example.test/")
        assertArrayEquals(
            RelayBearer.stablePeerIdForUrl("wss://only.example.test/"),
            b.peerIdForTest,
        )
        repeat(3) { assertEquals("wss://only.example.test/", b.resolveCandidateForTest()) }
    }

    @Test fun seedUrlFixesThePeerIdSoFailoverDoesNotChurnBookkeeping() {
        // The manager keys its bookkeeping on the synthetic peer id, so it must come from the SEED
        // and stay fixed. If it tracked the current endpoint, every failover would look to the
        // manager like a brand-new peer.
        val seed = "wss://seed.example.test/"
        var handOut = "wss://other.example.test/"
        val b = RelayBearer(seedUrl = seed, resolveUrl = { handOut }, reportOutcome = { _, _ -> })
        assertArrayEquals(
            "the peer id must follow the seed, not the resolved endpoint",
            RelayBearer.stablePeerIdForUrl(seed),
            b.peerIdForTest,
        )
        handOut = "wss://third.example.test/"
        assertArrayEquals(
            "changing the resolved endpoint must not change the peer id",
            RelayBearer.stablePeerIdForUrl(seed),
            b.peerIdForTest,
        )
    }

    @Test fun theResolverIsConsultedOnEveryAttemptAndPicksUpAChange() {
        // The property that makes failover work at all. A bearer that cached the URL would keep
        // dialing a corpse no matter what the pool said.
        val asked = mutableListOf<String>()
        var current = "wss://a.example.test/"
        val b = RelayBearer(
            seedUrl = current,
            resolveUrl = { asked.add(current); current },
            reportOutcome = { _, _ -> },
        )
        assertEquals("wss://a.example.test/", b.resolveCandidateForTest())
        current = "wss://b.example.test/"
        assertEquals(
            "a later attempt must use the newly resolved endpoint, with no re-registration",
            "wss://b.example.test/",
            b.resolveCandidateForTest(),
        )
        assertEquals(2, asked.size)
    }

    @Test fun nothingDialableResolvesToNullSoTheCallerWaits() {
        // Every pooled endpoint backed off is a WAIT state. `dial()` treats null as "retry on the
        // next backoff tick"; treating it as a stop would make a transient outage end reach until
        // restart. Blank is normalized to null so an empty pool answer cannot become a bogus dial.
        val b = RelayBearer(seedUrl = "wss://seed.example.test/", resolveUrl = { null })
        assertNull(b.resolveCandidateForTest())
        val blank = RelayBearer(seedUrl = "wss://seed.example.test/", resolveUrl = { "   " })
        assertNull("a blank answer must not become a dial target", blank.resolveCandidateForTest())
    }

    @Test fun outcomesAreAttributedToTheEndpointThatEarnedThem() {
        // Reporting against "whatever the pool returns now" instead of the endpoint that actually
        // failed would score the wrong relay down and could blacklist a healthy one.
        val reports = mutableListOf<Pair<String, Boolean>>()
        val b = RelayBearer(
            seedUrl = "wss://seed.example.test/",
            resolveUrl = { "wss://current.example.test/" },
            reportOutcome = { u, ok -> reports.add(u to ok) },
        )
        b.reportForTest("wss://died.example.test/", false)
        b.reportForTest("wss://worked.example.test/", true)
        assertEquals(
            listOf("wss://died.example.test/" to false, "wss://worked.example.test/" to true),
            reports,
        )
        assertTrue(
            "reporting must not be redirected to the currently-resolved endpoint",
            reports.none { it.first == "wss://current.example.test/" },
        )
    }
}
