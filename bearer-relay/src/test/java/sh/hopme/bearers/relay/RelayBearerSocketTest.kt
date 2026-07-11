package sh.hopme.bearers.relay

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import sh.hop.HopRole
import sh.hop.LinkSink
import java.util.concurrent.CopyOnWriteArrayList

/**
 * cov/android-bearers: drive the REAL RelayBearer reconnect state machine over an OkHttp MockWebServer
 * WebSocket, under Robolectric so android.util.Log is shadowed. Exercises the whole lifecycle that the
 * pure RelayBackoffTest can't reach: onOpen -> linkUp(DIALER), onMessage(binary/text) -> linkBytes,
 * send() -> a real WS frame the server receives, onClosing -> linkDown + reconnect (a fresh linkUp), and
 * the onFailure 429 rate-limit branch. The 20s stable-reset lambda is scheduled here (covered) but its
 * body is time-bound and pinned separately in RelayBackoffTest.
 */
@RunWith(RobolectricTestRunner::class)
class RelayBearerSocketTest {

    private val servers = ArrayList<MockWebServer>()
    private val bearers = ArrayList<RelayBearer>()

    @After fun tearDown() {
        bearers.forEach { runCatching { it.stop() } }
        servers.forEach { runCatching { it.shutdown() } }
    }

    private class Rec : LinkSink {
        val ups = CopyOnWriteArrayList<Triple<Long, HopRole, String>>()
        val bytes = CopyOnWriteArrayList<Pair<Long, ByteArray>>()
        val downs = CopyOnWriteArrayList<Long>()
        override fun linkUp(link: Long, role: HopRole, peerId: ByteArray) { ups.add(Triple(link, role, peerId.joinToString("") { "%02x".format(it) })) }
        override fun linkBytes(link: Long, b: ByteArray) { bytes.add(link to b) }
        override fun linkDown(link: Long) { downs.add(link) }
    }

    /// Captures the server-side WebSocket so a test can push frames / close, and records inbound frames.
    private class ServerWs : WebSocketListener() {
        @Volatile var socket: WebSocket? = null
        val received = CopyOnWriteArrayList<ByteArray>()
        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) { socket = webSocket }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) { received.add(bytes.toByteArray()) }
        override fun onMessage(webSocket: WebSocket, text: String) { received.add(text.toByteArray()) }
    }

    private fun waitUntil(ms: Long = 5000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(15) }
        return cond()
    }

    private fun server() = MockWebServer().also { servers.add(it); it.start() }
    private fun wsUrl(s: MockWebServer) = s.url("/relay").toString() // http:// - OkHttp upgrades it

    // ---- open -> linkUp, message -> linkBytes, send -> a real server-received frame ----------------

    @Test fun openSurfacesLinkUpThenMessagesAndSendFlow() {
        val s = server()
        val sws = ServerWs()
        s.enqueue(MockResponse().withWebSocketUpgrade(sws))
        val rec = Rec()
        val b = RelayBearer(wsUrl(s)).also { it.sink = rec }
        bearers.add(b)
        b.start()

        assertTrue("relay onOpen -> linkUp", waitUntil { rec.ups.isNotEmpty() })
        assertEquals("relay dials, so we are the Noise initiator", HopRole.DIALER, rec.ups.first().second)
        val link = rec.ups.first().first
        assertTrue("server saw the client connect", waitUntil { sws.socket != null })

        // server -> client, both binary and text, each surfaces one linkBytes.
        sws.socket!!.send(byteArrayOf(9, 8, 7).toByteString())
        sws.socket!!.send("hi-text")
        assertTrue("binary msg surfaced", waitUntil { rec.bytes.any { it.second.contentEquals(byteArrayOf(9, 8, 7)) } })
        assertTrue("text msg surfaced", waitUntil { rec.bytes.any { String(it.second) == "hi-text" } })

        // client -> server: send() puts a real binary frame on the socket.
        b.send(byteArrayOf(1, 2, 3, 4), link)
        assertTrue("server received the sent frame", waitUntil { sws.received.any { it.contentEquals(byteArrayOf(1, 2, 3, 4)) } })
    }

    // ---- server close -> linkDown + automatic reconnect (a fresh linkUp) --------------------------

    @Test fun serverCloseTriggersLinkDownThenReconnect() {
        val s = server()
        val first = ServerWs(); val second = ServerWs()
        s.enqueue(MockResponse().withWebSocketUpgrade(first))
        s.enqueue(MockResponse().withWebSocketUpgrade(second)) // the reconnect lands here
        val rec = Rec()
        val b = RelayBearer(wsUrl(s)).also { it.sink = rec }
        bearers.add(b)
        b.start()

        assertTrue("first link up", waitUntil { rec.ups.size == 1 })
        assertTrue(waitUntil { first.socket != null })
        first.socket!!.close(1000, "server-initiated")

        assertTrue("close surfaces linkDown", waitUntil { rec.downs.isNotEmpty() })
        // scheduleReconnect fires after ~1s + jitter; the second upgrade should bring a fresh link up.
        assertTrue("bearer auto-reconnects to a fresh link", waitUntil(8000) { rec.ups.size >= 2 })
    }

    // ---- onFailure 429 rate-limit branch ----------------------------------------------------------

    @Test fun http429UpgradeFailureIsHandledWithoutCrashing() {
        val s = server()
        // A non-101 upgrade response (429) makes OkHttp fail the WebSocket with that response attached,
        // driving RelayBearer.onFailure's 429 Retry-After branch. up was never true, so no linkDown; the
        // bearer schedules a (long) reconnect and stays healthy.
        s.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "5"))
        val rec = Rec()
        val b = RelayBearer(wsUrl(s)).also { it.sink = rec }
        bearers.add(b)
        b.start()
        assertTrue("the 429 request reached the server", waitUntil { s.requestCount >= 1 })
        // No linkUp (upgrade failed) and the bearer is still live (not torn down) after handling 429.
        Thread.sleep(300)
        assertTrue("no link came up from a 429", rec.ups.isEmpty())
        assertFalse("bearer survives a 429 without tearing down", b.isTornDown)
    }

    // ---- plain (non-429) connection failure -> the onFailure else branch + a scheduled reconnect ---

    @Test fun plainConnectionFailureIsHandledWithoutCrashing() {
        // Start then immediately shut a server so its port is closed: the dial gets connection-refused,
        // driving onFailure with a null response (the else branch, not the 429 path). The bearer logs and
        // schedules a backoff reconnect; it must not crash and must stay live.
        val s = server()
        val url = wsUrl(s)
        s.shutdown()
        val rec = Rec()
        val b = RelayBearer(url).also { it.sink = rec }
        bearers.add(b)
        b.start()
        Thread.sleep(500)
        assertTrue("a refused dial never surfaces a link", rec.ups.isEmpty())
        assertFalse("bearer survives a connection failure", b.isTornDown)
    }

    // ---- stop() on a live link surfaces one linkDown and tears the executor down -------------------

    @Test fun stopOnLiveLinkSurfacesLinkDownAndTearsDown() {
        val s = server()
        val sws = ServerWs()
        s.enqueue(MockResponse().withWebSocketUpgrade(sws))
        val rec = Rec()
        val b = RelayBearer(wsUrl(s)).also { it.sink = rec }
        bearers.add(b)
        b.start()
        assertTrue(waitUntil { rec.ups.isNotEmpty() })
        b.stop()
        assertTrue("stop() surfaces linkDown for the live link", waitUntil { rec.downs.isNotEmpty() })
        assertTrue("stop() releases the reconnect executor", b.isTornDown)
    }
}
