package sh.hopme.bearers.ble

// android-r2-07: the BLE one-pipe-per-peer dedup keep-rule, pulled into an Android-free file so it is
// unit-testable on a plain JVM (the rest of BleBearer.kt initializes Android-typed top-level vals whose
// file facade can't load under a stubbed android.jar). Mirrors the LAN bearer's LanDedup so both
// transports resolve a duplicate link-pair the SAME way.
//
// Discovery is symmetric: two peers can each dial the other, so BOTH an outbound (dialer) and an
// inbound (acceptor) L2CAP channel to the same peer come up. Exactly one must survive, and BOTH ends
// must independently pick the SAME survivor or they'll close opposite legs and thrash. The rule: keep
// the channel whose `isDialer` matches "am I the greater nodeId?". The greater-id side keeps ITS dialed
// channel; the lesser-id side keeps the channel it accepted (which is the greater side's dialed one).

/// Which of the two competing links to KEEP (the other is closed).
internal enum class BleDedupKeep { EXISTING, INCOMING }

internal object BleDedup {
    /// [amGreater] = nodeIdGreater(myId, peerId). [existingIsDialer]/[incomingIsDialer] = each competing
    /// link's role from MY perspective. Returns which link to keep. Matches the historical inline rule in
    /// BleBearer.onUp: keep whichever link's isDialer == amGreater; on the degenerate no-match case keep
    /// the incoming link (the `?: link` fallback).
    fun decide(amGreater: Boolean, existingIsDialer: Boolean, incomingIsDialer: Boolean): BleDedupKeep {
        val keepDialed = amGreater
        return when {
            existingIsDialer == keepDialed && incomingIsDialer != keepDialed -> BleDedupKeep.EXISTING
            incomingIsDialer == keepDialed && existingIsDialer != keepDialed -> BleDedupKeep.INCOMING
            existingIsDialer == keepDialed -> BleDedupKeep.EXISTING // both match (degenerate): keep existing
            else -> BleDedupKeep.INCOMING                          // neither matches: fall back to incoming
        }
    }
}
