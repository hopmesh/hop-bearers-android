package sh.hopme.bearers.relay

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import sh.hop.HopRole
import sh.hop.LinkSink
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tor support, Android side: the relay bearer can dial through a host-supplied SOCKS5 proxy (Orbot,
 * or Arti embedded in the app), which is what makes an `.onion` relay reachable without hop shipping
 * a Tor implementation.
 *
 * Two things actually have to hold, and both are asserted against a REAL loopback SOCKS5 proxy that
 * splices into a MockWebServer rather than a mock of one:
 *
 *  1. the connection goes through the proxy at all, and
 *  2. the TARGET HOSTNAME is handed to the proxy unresolved (SOCKS5 ATYP 0x03). This is the whole
 *     ballgame for `.onion`: no resolver on earth can answer that name, so if OkHttp resolved it
 *     locally the dial could never succeed.
 *
 * Mirrors bearers/apple/HopBearerRelay's RelayIntegrationTests.testOnionRelayDialsThroughASocksProxy;
 * per-platform bearer drift is a documented live-bug source (see bearers/CLAUDE.md).
 */
@RunWith(RobolectricTestRunner::class)
class RelayBearerSocksTest {

    /** A v3-shaped onion name. It resolves nowhere, which is exactly why it proves the point. */
    private val onionHost = "i3azam4xowcraffcdopctb4uq7wq23uhi3azam4xowcraffcdopctb4d.onion"

    private val servers = ArrayList<MockWebServer>()
    private val bearers = ArrayList<RelayBearer>()
    private val proxies = ArrayList<Socks5TestProxy>()

    @After fun tearDown() {
        bearers.forEach { runCatching { it.stop() } }
        proxies.forEach { runCatching { it.close() } }
        servers.forEach { runCatching { it.shutdown() } }
    }

    // ---- the resolved setting: absent = direct, good = proxy, present-but-broken = never dial ------

    @Test fun settingResolvesTheThreeStates() {
        assertTrue(SocksProxySetting.of(null) is SocksProxySetting.Direct)
        assertTrue(SocksProxySetting.of("") is SocksProxySetting.Direct)
        assertTrue(SocksProxySetting.of("   ") is SocksProxySetting.Direct)

        val via = SocksProxySetting.of("127.0.0.1:9050")
        assertTrue(via is SocksProxySetting.Via)
        val addr = (via as SocksProxySetting.Via).proxy.address() as InetSocketAddress
        assertEquals(9050, addr.port)
        assertTrue(SocksProxySetting.of("[::1]:9050") is SocksProxySetting.Via)
    }

    @Test fun aConfiguredButBrokenSpecIsUnusableNotDirect() {
        // The reason this is three states and not a nullable proxy. A typo must NOT read as "the
        // user did not want a proxy", or one bad character silently puts relay traffic on the
        // clearnet while the user believes it is proxied.
        for (bad in listOf("127.0.0.1;9050", "127.0.0.1", ":9050", "127.0.0.1:", "127.0.0.1:0",
                           "127.0.0.1:notaport", "127.0.0.1:99999", "::1:9050", "[::1]9050")) {
            assertTrue("'$bad' must be unusable", SocksProxySetting.of(bad) is SocksProxySetting.Unusable)
        }
    }

    @Test fun theClientCarriesTheProxyOnlyWhenOneIsConfigured() {
        val direct = RelayBearer("ws://relay.example.test/").also { bearers.add(it) }
        assertNull("a bearer with no proxy configured must dial direct", direct.clientProxyForTest)

        val proxied = RelayBearer("ws://relay.example.test/", socksProxy = "127.0.0.1:9050")
            .also { bearers.add(it) }
        assertEquals(java.net.Proxy.Type.SOCKS, proxied.clientProxyForTest?.type())
    }

    // ---- the real thing: an .onion dial through a real SOCKS5 proxy --------------------------------

    @Test fun anOnionRelayDialsThroughTheProxyWithAnUnresolvedHostname() {
        val upstream = MockWebServer().also { servers.add(it); it.start() }
        val sws = ServerWs()
        upstream.enqueue(MockResponse().withWebSocketUpgrade(sws))
        val proxy = Socks5TestProxy(upstream.port).also { proxies.add(it) }

        val rec = Rec()
        val bearer = RelayBearer(
            "ws://$onionHost/_hop",
            socksProxy = "127.0.0.1:${proxy.port}",
        ).also { it.sink = rec; bearers.add(it) }
        bearer.start()

        assertTrue("the upgrade completes through the proxy and surfaces linkUp",
                   waitUntil(10_000) { rec.ups.isNotEmpty() })
        assertEquals("we dialed out, so we are still the Noise initiator", HopRole.DIALER, rec.ups.first().second)

        // The property that makes .onion work at all: the NAME went to the proxy, unresolved.
        assertEquals("SOCKS5 ATYP 0x03 = domain name, not a resolved address", 0x03, proxy.addressType)
        assertEquals("the full onion hostname is handed to the proxy to resolve", onionHost, proxy.requestedHost)
        assertEquals("the URL's implicit ws:// port rides along", 80, proxy.requestedPort)

        // And it is a working link, not just an open socket.
        assertTrue(waitUntil { sws.socket != null })
        sws.socket!!.send(okio.ByteString.of(0x07, 0x08))
        assertTrue("bytes flow over the proxied link like any other relay link",
                   waitUntil { rec.bytes.any { it.second.contentEquals(byteArrayOf(0x07, 0x08)) } })
    }

    @Test fun aBrokenProxySpecRefusesToDialInsteadOfGoingDirect() {
        // FAIL CLOSED. A configured-but-unusable proxy must not degrade into a clearnet dial: the
        // user's IP would be exposed to the relay while they believe it is not. The refused attempt
        // is still reported, so the pool scores the endpoint and a UI can show "no reach".
        val server = MockWebServer().also { servers.add(it); it.start() }
        val outcomes = CopyOnWriteArrayList<Pair<String, Boolean>>()
        val url = server.url("/relay").toString()
        val rec = Rec()
        val bearer = RelayBearer(
            seedUrl = url,
            resolveUrl = { url },
            reportOutcome = { u, ok -> outcomes.add(u to ok) },
            socksProxy = "127.0.0.1;9050",   // a semicolon instead of a colon
        ).also { it.sink = rec; bearers.add(it) }
        bearer.start()

        assertTrue("the refused dial is reported as a failure against the endpoint",
                   waitUntil { outcomes.any { it.first == url && !it.second } })
        assertEquals("no socket was opened to the relay at all", 0, server.requestCount)
        assertTrue("and no link was surfaced", rec.ups.isEmpty())
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private fun waitUntil(ms: Long = 5000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(15) }
        return cond()
    }

    private class Rec : LinkSink {
        val ups = CopyOnWriteArrayList<Triple<Long, HopRole, ByteArray>>()
        val bytes = CopyOnWriteArrayList<Pair<Long, ByteArray>>()
        val downs = CopyOnWriteArrayList<Long>()
        override fun linkUp(link: Long, role: HopRole, peerId: ByteArray) { ups.add(Triple(link, role, peerId)) }
        override fun linkBytes(link: Long, b: ByteArray) { bytes.add(link to b) }
        override fun linkDown(link: Long) { downs.add(link) }
    }

    private class ServerWs : WebSocketListener() {
        @Volatile var socket: WebSocket? = null
        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) { socket = webSocket }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {}
        override fun onMessage(webSocket: WebSocket, text: String) {}
    }

    /**
     * Just enough of RFC 1928 to stand in for a local Tor listener: no-auth handshake, a CONNECT
     * request whose target it records, then a byte splice to [upstreamPort]. Deliberately a real
     * socket rather than a mock, because the claim under test is about what OkHttp actually puts on
     * the wire.
     */
    private class Socks5TestProxy(private val upstreamPort: Int) : AutoCloseable {
        private val server = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        private val sockets = CopyOnWriteArrayList<Socket>()
        private val atyp = AtomicInteger(-1)
        private val portSeen = AtomicInteger(-1)
        @Volatile private var host: String? = null
        @Volatile private var running = true

        val port: Int get() = server.localPort
        val addressType: Int get() = atyp.get()
        val requestedHost: String? get() = host
        val requestedPort: Int get() = portSeen.get()

        init {
            Thread({ acceptLoop() }, "test.socks.proxy").apply { isDaemon = true }.start()
        }

        private fun acceptLoop() {
            while (running) {
                val client = runCatching { server.accept() }.getOrNull() ?: return
                sockets.add(client)
                Thread({ runCatching { serve(client) } }, "test.socks.conn").apply { isDaemon = true }.start()
            }
        }

        private fun serve(client: Socket) {
            val ins = DataInputStream(client.getInputStream())
            val outs = client.getOutputStream()

            // Greeting: VER NMETHODS METHODS... -> "no authentication required".
            ins.readByte()
            val nMethods = ins.readByte().toInt() and 0xff
            ins.readFully(ByteArray(nMethods))
            outs.write(byteArrayOf(0x05, 0x00)); outs.flush()

            // CONNECT: VER CMD RSV ATYP ADDR PORT.
            ins.readByte(); ins.readByte(); ins.readByte()
            val a = ins.readByte().toInt() and 0xff
            atyp.set(a)
            when (a) {
                0x03 -> {
                    val len = ins.readByte().toInt() and 0xff
                    val name = ByteArray(len); ins.readFully(name)
                    host = String(name)
                }
                0x01 -> ins.readFully(ByteArray(4))
                else -> ins.readFully(ByteArray(16))
            }
            portSeen.set(((ins.readByte().toInt() and 0xff) shl 8) or (ins.readByte().toInt() and 0xff))

            val upstream = Socket("127.0.0.1", upstreamPort).also { sockets.add(it) }
            outs.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0, 0)); outs.flush()
            pump(client, upstream)
            pump(upstream, client)
        }

        private fun pump(from: Socket, to: Socket) {
            Thread({
                runCatching {
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = from.getInputStream().read(buf)
                        if (n < 0) break
                        to.getOutputStream().write(buf, 0, n)
                        to.getOutputStream().flush()
                    }
                }
                runCatching { to.close() }
            }, "test.socks.pump").apply { isDaemon = true }.start()
        }

        override fun close() {
            running = false
            runCatching { server.close() }
            sockets.forEach { runCatching { it.close() } }
        }
    }
}
