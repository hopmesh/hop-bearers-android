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

// The backoff constants + the pure reconnect-schedule arithmetic live in RelayBackoff.kt (Android-free
// so they're unit-testable). RELAY_BACKOFF_MIN_MS / _MAX_MS / _STABLE_MS / _DEAD_AFTER / _DEAD_MS are
// defined there; RelayBearer owns only the state machine that calls RelayBackoff.step().

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
                    RelayBackoff.retryAfterFrom429(response.header("Retry-After")?.toLongOrNull())
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
        // F-13/android-09: pure schedule (RelayBackoff.step): honor a one-shot 429 Retry-After, else
        // exponential backoff doubling to the cap, else the multi-minute dead ceiling once the endpoint
        // has failed RELAY_DEAD_AFTER dials in a row. Count every reconnect as a consecutive failed dial;
        // a stable link (onOpen + RELAY_STABLE_MS) resets both the base and the streak.
        deadStreak++
        val step = RelayBackoff.step(deadStreak, backoffMs, retryAfterMs)
        retryAfterMs = null       // a Retry-After is consumed exactly once
        backoffMs = step.nextBackoffMs
        // Always add jitter so an Android sub-fleet whose sockets drop together (e.g. a relay redeploy)
        // doesn't reconnect in lockstep (Android previously had none).
        val jitter = (Math.random() * 1000).toLong()
        exec.schedule({ if (started && ws == null) dial() }, step.delayMs + jitter, TimeUnit.MILLISECONDS)
    }
}
