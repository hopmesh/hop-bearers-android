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
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
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

    private fun newExec(): ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "hop.relay.bearer") }

    /// Serial home for all bearer state (callbacks hop here) + the backoff reconnect timer. A `var`
    /// so a stopped-then-restarted bearer works: stop() shuts this down to release the thread (the
    /// leak fix), and start() installs a FRESH one instead of being a permanent no-op. Pre-r5 this was
    /// a `val` shut down on stop(), which released the thread but left start() a dead no-op and left
    /// in-flight OkHttp callbacks throwing RejectedExecutionException onto the shut executor.
    private var exec: ScheduledExecutorService = newExec()

    /// Post a task onto a specific executor, dropping it if that executor has been shut down. OkHttp
    /// delivers WebSocket callbacks on its own dispatcher threads, so a callback that races stop()
    /// (or arrives from an old connection after a restart, targeting the old shut exec) must be a
    /// silent no-op, never a RejectedExecutionException that tears down the dispatcher thread.
    private fun postTo(ex: ScheduledExecutorService, task: () -> Unit) {
        try {
            ex.execute(task)
        } catch (_: RejectedExecutionException) {
            // bearer was stopped (this exec is shut): drop the racing callback
        }
    }

    private var ws: WebSocket? = null
    private var started = false
    private var up = false
    private var backoffMs = RELAY_BACKOFF_MIN_MS
    private var deadStreak = 0                                                  // android-09: consecutive failed dials
    private var stableFuture: java.util.concurrent.ScheduledFuture<*>? = null  // F-13: stable→reset backoff
    private var retryAfterMs: Long? = null                                     // F-13: 429 Retry-After

    // ---- Bearer ----

    override fun start() {
        // Restartable: if a prior stop() shut the executor down, install a fresh one so start() after
        // stop() actually reconnects (pre-r5 this was a permanent no-op). A running bearer keeps its
        // executor; the `if (started)` guard inside makes a redundant start() harmless.
        if (exec.isShutdown) exec = newExec()
        exec.execute {
            if (started) return@execute
            started = true
            Log.i(TAG, "relay node-start url=$relayUrl peer=${peerId.toHex().take(8)}")
            dial()
        }
    }

    override fun stop() {
        // Idempotent: a second stop() (BearerManager.stop then a service teardown) must be a no-op, not a
        // RejectedExecutionException on the already-shut executor.
        if (exec.isShutdown) return
        // Tear down ON `exec` (so the socket close + linkDown observe the same serial state), then shut
        // the executor + OkHttp down AFTER that task runs. Prior bug: neither the single-thread executor
        // nor the OkHttp dispatcher/connection pool were ever released, so every disable/enable leaked a
        // "hop.relay.bearer" thread + OkHttp resources. shutdown() lets the queued teardown run first,
        // then stops accepting new work; a live link still gets its linkDown.
        exec.execute {
            started = false
            stableFuture?.cancel(false); stableFuture = null
            ws?.close(1001, "stop")
            ws = null
            if (up) { up = false; sink?.linkDown(linkId) }
        }
        // Shut this bearer's serial executor so its "hop.relay.bearer" thread is released on every
        // stop (the leak fix); the queued teardown above runs first, then no new work is accepted. A
        // later start() installs a fresh executor. Evict the connection pool so the closed socket's
        // resources are freed now. We do NOT shut down client.dispatcher.executorService: that pool is
        // shared with a possible restart, and OkHttp reaps its own idle threads (~60s), so shutting it
        // would break a restart's dial() without preventing a real leak.
        exec.shutdown()
        client.connectionPool.evictAll()
    }

    override fun send(bytes: ByteArray, link: LinkId) {
        if (link != linkId) return
        ws?.send(ByteString.of(*bytes))   // one node packet = one WS binary frame; OkHttp send is thread-safe
    }

    /// Test hook (bearer-relay unit tests): true once stop() has shut the reconnect executor down. Lets a
    /// radio-free JVM test assert the leak fix (the executor is actually released on stop), since exec is
    /// otherwise private and its thread would leak silently.
    internal val isTornDown: Boolean get() = exec.isShutdown

    // ---- dial / down / reconnect (all on `exec`) ----

    private fun dial() {
        if (!started) return
        Log.i(TAG, "relay dial $relayUrl")
        // Capture the executor this connection belongs to. All of its OkHttp callbacks post here via
        // postTo(), so a callback racing stop() (or from this old connection after a restart onto a
        // fresh exec) lands on the shut executor and is dropped, never a RejectedExecutionException.
        val dialExec = exec
        ws = client.newWebSocket(Request.Builder().url(relayUrl).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = run {
                postTo(dialExec) {
                    up = true
                    Log.i(TAG, "relay link-up peer=${peerId.toHex().take(8)}")
                    sink?.linkUp(linkId, HopRole.DIALER, peerId)   // dialer = Noise initiator
                    // F-13: reset backoff only after the link has been stable a while, not on open — a
                    // relay that accepts then immediately drops isn't then re-dialed at the 1s floor forever.
                    stableFuture?.cancel(false)
                    stableFuture = try {
                        dialExec.schedule(
                            { if (up) { backoffMs = RELAY_BACKOFF_MIN_MS; deadStreak = 0 } }, // android-09: a stable link clears the dead streak
                            RELAY_STABLE_MS, TimeUnit.MILLISECONDS,
                        )
                    } catch (_: RejectedExecutionException) {
                        null // stopped between onOpen and the schedule: no stable-reset needed
                    }
                }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = run {
                postTo(dialExec) { sink?.linkBytes(linkId, bytes.toByteArray()) }
            }
            override fun onMessage(webSocket: WebSocket, text: String) = run {
                postTo(dialExec) { sink?.linkBytes(linkId, text.toByteArray()) }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = run {
                postTo(dialExec) { Log.i(TAG, "relay closing: $code $reason"); down() }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = run {
                // F-13: honor a 429 Retry-After from the upgrade response instead of hammering.
                val ra: Long? = if (response?.code == 429) {
                    RelayBackoff.retryAfterFrom429(response.header("Retry-After")?.toLongOrNull())
                } else {
                    null
                }
                postTo(dialExec) {
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
