package sh.hopme.bearers.relay

import android.util.Log
import sh.hop.Bearer
import sh.hop.LinkId
import sh.hop.HopRole
import sh.hop.LinkSink
import sh.hop.TAG
import sh.hop.toHex
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// RelayBearer — the cloud-relay transport as its OWN library (depends only on :bearer-core). The Android
// mirror of apple/HopBearers' RelayBearer. It is the SIMPLEST bearer: ONE outbound WebSocket to the
// backbone relay, no peer discovery, no HELLO, no length framing (a WS message already frames exactly one
// node packet), no keepalive/dedup. The relay's lifecycle maps 1:1 to a single node link:
//
//   - start()  -> dial the relay over an OkHttp WebSocket.
//   - onOpen   -> sink.linkUp(linkId, DIALER, peerId) — we dialed, so we're the Noise initiator.
//   - onMessage-> sink.linkBytes(linkId, bytes) — one WS frame = one node packet.
//   - onClosing/onFailure -> sink.linkDown(linkId) + reconnect with exponential backoff (the device
//     "check-in" that pulls queued mail and stays reachable across the internet).
//   - send     -> ws.send(bytes).
//   - stop()   -> close the socket; the sink gets linkDown for the live link.
//
// The node identifies the relay via Noise over this link, so the consumer needs no real peer identity
// from the transport — only a STABLE synthetic peerId for the BearerManager's bookkeeping. We derive it
// deterministically from the relay URL (SHA-256 prefix) so it's identical every reconnect; the node
// ignores it. Names nothing about BLE/LAN — written purely against start/stop/send/sink.
//
// THREADING: OkHttp delivers WebSocketListener callbacks from its own dispatcher threads; we hop each
// onto a single-thread executor so all bearer state lives on one thread (no locks). OkHttp's send is
// itself thread-safe, so send() may be called from any thread.

private const val RELAY_BACKOFF_MIN_MS = 1_000L
private const val RELAY_BACKOFF_MAX_MS = 30_000L
private const val RELAY_STABLE_MS = 20_000L   // F-13: only reset backoff after the link holds this long
// android-09: once the relay has been unreachable for many CONSECUTIVE dials (the fleet is torn down,
// not a transient blip), stop waking the radio every ~30s and back off to several minutes. A live
// link that stays up past RELAY_STABLE_MS resets both the delay AND this streak.
private const val RELAY_DEAD_AFTER = 8          // consecutive failed dials before the long ceiling kicks in
private const val RELAY_BACKOFF_DEAD_MS = 300_000L  // ~5 min ceiling for a clearly-dead endpoint

class RelayBearer(private val relayUrl: String) : Bearer {
    override var sink: LinkSink? = null
    /// Short transport tag for the consumer's UI (Bearer contract). The cloud relay link surfaces as "Relay".
    override val transportName = "Relay"

    /// ONE link — one WebSocket. The BearerManager translates this local id into its global id space and
    /// mints a fresh global on each reconnect (linkDown forgets the old mapping), so the node sees each
    /// reconnection as a new link, which is correct.
    private val linkId: LinkId = 1L
    /// Stable synthetic peer id (16 bytes) for the manager's bookkeeping — derived from the relay URL so
    /// it's identical every reconnect. The node ignores it (it identifies the relay via Noise).
    private val peerId: ByteArray =
        MessageDigest.getInstance("SHA-256").digest(relayUrl.toByteArray()).copyOf(16)

    private val client = OkHttpClient.Builder().build()
    /// Serial home for all bearer state (callbacks hop here) + the backoff reconnect timer.
    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "hop.relay.bearer") }

    private var ws: WebSocket? = null
    private var started = false
    private var up = false
    private var backoffMs = RELAY_BACKOFF_MIN_MS
    private var deadStreak = 0                                                  // android-09: consecutive failed dials
    private var stableFuture: java.util.concurrent.ScheduledFuture<*>? = null  // F-13: stable→reset backoff
    private var retryAfterMs: Long? = null                                     // F-13: 429 Retry-After

    // ---- Bearer ----

    override fun start() {
        exec.execute {
            if (started) return@execute
            started = true
            Log.i(TAG, "relay node-start url=$relayUrl peer=${peerId.toHex().take(8)}")
            dial()
        }
    }

    override fun stop() {
        exec.execute {
            started = false
            ws?.close(1001, "stop")
            ws = null
            if (up) { up = false; sink?.linkDown(linkId) }
        }
    }

    override fun send(bytes: ByteArray, link: LinkId) {
        if (link != linkId) return
        ws?.send(ByteString.of(*bytes))   // one node packet = one WS binary frame; OkHttp send is thread-safe
    }

    // ---- dial / down / reconnect (all on `exec`) ----

    private fun dial() {
        if (!started) return
        Log.i(TAG, "relay dial $relayUrl")
        ws = client.newWebSocket(Request.Builder().url(relayUrl).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = run {
                exec.execute {
                    up = true
                    Log.i(TAG, "relay link-up peer=${peerId.toHex().take(8)}")
                    sink?.linkUp(linkId, HopRole.DIALER, peerId)   // dialer = Noise initiator
                    // F-13: reset backoff only after the link has been stable a while, not on open — a
                    // relay that accepts then immediately drops isn't then re-dialed at the 1s floor forever.
                    stableFuture?.cancel(false)
                    stableFuture = exec.schedule(
                        { if (up) { backoffMs = RELAY_BACKOFF_MIN_MS; deadStreak = 0 } }, // android-09: a stable link clears the dead streak
                        RELAY_STABLE_MS, TimeUnit.MILLISECONDS,
                    )
                }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = run {
                exec.execute { sink?.linkBytes(linkId, bytes.toByteArray()) }
            }
            override fun onMessage(webSocket: WebSocket, text: String) = run {
                exec.execute { sink?.linkBytes(linkId, text.toByteArray()) }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = run {
                exec.execute { Log.i(TAG, "relay closing: $code $reason"); down() }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = run {
                // F-13: honor a 429 Retry-After from the upgrade response instead of hammering.
                val ra: Long? = if (response?.code == 429) {
                    (response.header("Retry-After")?.toLongOrNull()?.times(1000)) ?: RELAY_BACKOFF_MAX_MS
                } else {
                    null
                }
                exec.execute {
                    if (ra != null) { retryAfterMs = ra; Log.w(TAG, "relay 429 rate-limited; backing off ${ra}ms") }
                    else Log.w(TAG, "relay failed: ${t.message}")
                    down()
                }
            }
        })
    }

    /// Surface linkDown once (idempotent), drop the socket, then schedule a backoff reconnect.
    private fun down() {
        stableFuture?.cancel(false); stableFuture = null
        ws = null
        if (up) { up = false; sink?.linkDown(linkId) }
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (!started) return
        // F-13: honor a server-driven Retry-After when present; else exponential backoff. Always add
        // jitter so an Android sub-fleet whose sockets drop together (e.g. a relay redeploy) doesn't
        // reconnect in lockstep — Android previously had none.
        // android-09: count every reconnect as a consecutive failed dial. A stable link resets it.
        deadStreak++
        val base = retryAfterMs
        val delay: Long
        if (base != null) {
            retryAfterMs = null
            delay = base
        } else if (deadStreak >= RELAY_DEAD_AFTER) {
            // Endpoint has been dead for many attempts (fleet torn down): stop waking the radio every
            // ~30s and hold at the multi-minute ceiling until it comes back.
            delay = RELAY_BACKOFF_DEAD_MS
            backoffMs = RELAY_BACKOFF_MAX_MS
        } else {
            delay = backoffMs
            backoffMs = (backoffMs * 2).coerceAtMost(RELAY_BACKOFF_MAX_MS)
        }
        val jitter = (Math.random() * 1000).toLong()
        exec.schedule({ if (started && ws == null) dial() }, delay + jitter, TimeUnit.MILLISECONDS)
    }
}
