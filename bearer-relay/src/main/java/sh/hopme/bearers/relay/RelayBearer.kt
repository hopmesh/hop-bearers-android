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
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

// RelayBearer, the cloud-relay transport as its OWN library (depends only on :bearer-core). The Android
// mirror of apple/HopBearers' RelayBearer. It is the SIMPLEST bearer: ONE outbound WebSocket to the
// backbone relay, no peer discovery, no HELLO, no length framing (a WS message already frames exactly one
// node packet), no keepalive/dedup. The relay's lifecycle maps 1:1 to a single node link:
//
//   - start()  -> dial the relay over an OkHttp WebSocket.
//   - onOpen   -> sink.linkUp(linkId, DIALER, peerId): we dialed, so we're the Noise initiator.
//   - onMessage-> sink.linkBytes(linkId, bytes): one WS frame = one node packet.
//   - onClosing/onFailure -> sink.linkDown(linkId) + reconnect with exponential backoff (the device
//     "check-in" that pulls queued mail and stays reachable across the internet).
//   - send     -> ws.send(bytes).
//   - stop()   -> close the socket; the sink gets linkDown for the live link.
//
// The node identifies the relay via Noise over this link, so the consumer needs no real peer identity
// from the transport, just a STABLE synthetic peerId for the BearerManager's bookkeeping. We derive it
// deterministically from the relay URL (SHA-256 prefix) so it's identical every reconnect; the node
// ignores it. Names nothing about BLE/LAN, written purely against start/stop/send/sink.
//
// THREADING: OkHttp delivers WebSocketListener callbacks from its own dispatcher threads; we hop each
// onto a single-thread executor so all bearer state lives on one thread (no locks). OkHttp's send is
// itself thread-safe, so send() may be called from any thread.

// The backoff constants + the pure reconnect-schedule arithmetic live in RelayBackoff.kt (Android-free
// so they're unit-testable). RELAY_BACKOFF_MIN_MS / _MAX_MS / _STABLE_MS / _DEAD_AFTER / _DEAD_MS are
// defined there; RelayBearer owns only the state machine that calls RelayBackoff.step().
//
// TOR: there is no Tor code here and there should not be. A host that wants relay traffic to ride Tor
// runs a proxy (Orbot, or Arti embedded in the app) and passes its `host:port` in as `socksProxy`; the
// dial string then just happens to be a `.onion` URL, which the node's pool and this bearer treat like
// any other. See docs/tor.md for what that does and, importantly, does not hide.

/**
 * What the host's proxy setting resolved to, decided ONCE at construction.
 *
 * The third case is the point. A setting that was supplied but does not parse must never quietly
 * become "dial direct": that is a typo turning into a clearnet connection the user believes is
 * proxied. Three explicit states leave the dial path no "no proxy configured" branch it can fall
 * into by accident. Mirrors `SocksProxySetting` in bearers/apple/HopBearerRelay; keep the two in
 * step (per-platform bearer drift is a documented live-bug source, see bearers/CLAUDE.md).
 */
sealed interface SocksProxySetting {
    /** No proxy configured. Dial normally (the default for every existing caller). */
    object Direct : SocksProxySetting
    /** Dial every relay connection through this proxy. */
    data class Via(val proxy: Proxy) : SocksProxySetting
    /** A proxy WAS configured but the spec is unusable. Never dial. */
    object Unusable : SocksProxySetting

    companion object {
        /**
         * Resolve a host-supplied `host:port` spec, e.g. `127.0.0.1:9050` for a local Tor listener,
         * or `[::1]:9050`. Null/blank means no proxy was asked for; anything else must parse or the
         * bearer refuses to dial at all.
         */
        fun of(spec: String?): SocksProxySetting {
            val raw = spec?.trim().orEmpty()
            if (raw.isEmpty()) return Direct
            val host: String
            val portText: String
            if (raw.startsWith("[")) {
                // Bracketed IPv6: the colons inside the address are not the separator.
                val close = raw.indexOf(']')
                if (close < 0 || close + 1 >= raw.length || raw[close + 1] != ':') return Unusable
                host = raw.substring(1, close)
                portText = raw.substring(close + 2)
            } else {
                if (raw.count { it == ':' } != 1) return Unusable
                host = raw.substringBefore(':')
                portText = raw.substringAfter(':')
            }
            val port = portText.toIntOrNull() ?: return Unusable
            if (host.isEmpty() || port !in 1..65535) return Unusable
            // The PROXY's own address is resolved here (it is ours, typically loopback). The TARGET
            // host is not: OkHttp hands an unresolved address to a SOCKS proxy, which is what lets a
            // .onion name, that no resolver can answer, be resolved by Tor itself.
            return Via(Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, port)))
        }
    }
}

/**
 * `resolveUrl` is called PER DIAL ATTEMPT rather than fixed at construction, which is what makes
 * relay failover possible without a live re-register on the shared `BearerManager` (which has none).
 * A fixed URL meant a dead relay was retried forever and the only way to move was an app restart.
 * Returning null or blank means "nothing dialable right now": a WAIT (retry on the next backoff
 * tick), never a stop, or a transient outage would end reach permanently.
 *
 * `reportOutcome(url, ok)` feeds each attempt back to the host so the node's §19 relay pool can
 * score endpoints. Both default to fixed-URL behavior, so existing callers are unchanged.
 *
 * `socksProxy` is a `host:port` spec (e.g. a local Tor listener at `127.0.0.1:9050`); null or blank
 * dials direct, and an unparseable value refuses to dial rather than falling back to it.
 *
 * Mirrors bearers/apple/HopBearerRelay RelayBearer. Keep the two in step; per-platform bearer drift
 * is a documented live-bug source (see bearers/CLAUDE.md).
 */
class RelayBearer(
    seedUrl: String,
    private val resolveUrl: () -> String? = { seedUrl },
    private val reportOutcome: (String, Boolean) -> Unit = { _, _ -> },
    socksProxy: String? = null,
) : Bearer {
    override var sink: LinkSink? = null
    /// Short transport tag for the consumer's UI (Bearer contract). The cloud relay link surfaces as "Relay".
    override val transportName = "Relay"

    /// ONE link, one WebSocket. The BearerManager translates this local id into its global id space and
    /// mints a fresh global on each reconnect (linkDown forgets the old mapping), so the node sees each
    /// reconnection as a new link, which is correct.
    private val linkId: LinkId = 1L
    /// Stable synthetic peer id (16 bytes) for the manager's bookkeeping. The node ignores it (it
    /// identifies the relay via Noise). Derived from the SEED url and then held fixed: the manager keys its bookkeeping on this, so
    /// it must not change when we fail over to a different endpoint.
    private val peerId: ByteArray = stablePeerIdForUrl(seedUrl)
    /// The url the CURRENT attempt used, so an outcome is attributed to the endpoint that earned it.
    private var currentUrl: String? = null

    /// How the host's proxy setting resolved. Decided once here so no dial can re-interpret it.
    private val socks: SocksProxySetting = SocksProxySetting.of(socksProxy)

    private val client = OkHttpClient.Builder()
        .apply { (socks as? SocksProxySetting.Via)?.let { proxy(it.proxy) } }
        .build()

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
            Log.i(TAG, "relay node-start peer=${peerId.toHex().take(8)}")
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
        // later start() installs a fresh executor. We do NOT shut down client.dispatcher.executorService:
        // that pool is shared with a possible restart, and OkHttp reaps its own idle threads (~60s), so
        // shutting it would break a restart's dial() without preventing a real leak.
        exec.shutdown()
        // r6-01: BLOCK until the queued teardown task has actually finished before returning. Without
        // this, a stop() -> start() sequence (BearerManager fans both out to the SAME instance) could
        // leave the old exec's teardown running on one thread while the new exec's dial() runs on
        // another, racing over the non-volatile started/up/ws/stableFuture state. awaitTermination
        // makes stop() fully synchronous so start() always sees a quiesced bearer; the teardown task is
        // a socket close + one linkDown, so it completes in well under the cap.
        runCatching { exec.awaitTermination(2, TimeUnit.SECONDS) }
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

    /// Test seam: resolve one dial candidate exactly as `dial()` does, WITHOUT touching a socket.
    /// Keeps the resolution contract deterministically testable on the JVM: a real dial would need
    /// network timing and sleeps, which bearers/CLAUDE.md warns flakes in these unit tests. The
    /// socket-level behavior is covered end to end on the Apple side against a loopback server.
    internal fun resolveCandidateForTest(): String? = resolveUrl()?.takeIf { it.isNotBlank() }

    /// Test seam: the report path `dial()`/`down()` use, so attribution is assertable without a dial.
    internal fun reportForTest(url: String, ok: Boolean) = reportOutcome(url, ok)

    /// Test seam: the synthetic peer id, so a test can assert it follows the SEED across failover
    /// (the manager keys its bookkeeping on it, so it must not churn when the endpoint changes).
    internal val peerIdForTest: ByteArray get() = peerId

    /// Test seam: the resolved proxy setting and the HTTP client it produced, so a test can assert
    /// the client really carries the SOCKS proxy (and that a direct bearer carries none) without
    /// having to open a socket to find out.
    internal val socksForTest: SocksProxySetting get() = socks
    internal val clientProxyForTest: Proxy? get() = client.proxy

    internal companion object {
        /// The peer-id derivation, exposed so a test can compute the expected value rather than
        /// re-implementing SHA-256 and drifting from it.
        internal fun stablePeerIdForUrl(url: String): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).copyOf(16)
    }

    // ---- dial / down / reconnect (all on `exec`) ----

    private fun dial() {
        if (!started) return
        val candidate = resolveUrl()
        if (candidate.isNullOrBlank()) {
            // Every pooled endpoint is backed off. Wait for the next tick; do NOT stop.
            currentUrl = null
            scheduleReconnect()
            return
        }
        currentUrl = candidate
        if (socks is SocksProxySetting.Unusable) {
            // The host asked for every relay byte to go through a proxy and the spec is unusable.
            // FAIL CLOSED: dialing direct would put the connection on the clearnet, which is the
            // exact thing the caller configured a proxy to avoid, and it would do so silently.
            // down() reports the attempt as a failure against this endpoint, which is honest, from
            // this node the endpoint really is unreachable, and it gives the UI its "no reach"
            // signal instead of a pool that looks healthy while nothing ever connects.
            Log.w(TAG, "relay dial refused: SOCKS proxy configured but unusable")
            down()
            return
        }
        Log.i(TAG, "relay dial $candidate")
        // Capture the executor this connection belongs to. All of its OkHttp callbacks post here via
        // postTo(), so a callback racing stop() (or from this old connection after a restart onto a
        // fresh exec) lands on the shut executor and is dropped, never a RejectedExecutionException.
        val dialExec = exec
        ws = client.newWebSocket(Request.Builder().url(candidate).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = run {
                postTo(dialExec) {
                    up = true
                    // Score the endpoint that actually answered, not whatever the pool returns now.
                    currentUrl?.let { reportOutcome(it, true) }
                    Log.i(TAG, "relay link-up peer=${peerId.toHex().take(8)}")
                    sink?.linkUp(linkId, HopRole.DIALER, peerId)   // dialer = Noise initiator
                    // F-13: reset backoff only after the link has been stable a while, not on open, a
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
        // Report BEFORE scheduling the next attempt, so the pool has already scored this endpoint
        // down by the time dial() asks it where to go. Reporting after would re-pick the corpse.
        currentUrl?.let { reportOutcome(it, false) }
        currentUrl = null
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
