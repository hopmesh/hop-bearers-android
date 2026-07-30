package sh.hopme.bearers.ble

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import sh.hop.HopRole
import sh.hop.LinkSink

/**
 * PLAT-001: disabling the BLE transport must actually stop it.
 *
 * The bug: a link was registered with the bearer only in onUp (i.e. at HELLO), so a socket that was
 * up but still handshaking when the user switched the transport off was in no bearer map, closeAll()
 * could not reach it, its rx/tx threads and 1 Hz keepalive kept it alive, and its HELLO then surfaced
 * a link on a transport the UI was rendering as "disabled". Android already had half of this fix
 * (Central.stopped gating tryDial after the Pixel 7 regression), but the accept loop and the blocking
 * l2cap-dial thread both walk past that gate with a socket already in hand.
 *
 * These drive the REAL BleBearer.adopt / onUp / onClose / closeAll / stop through the BleLink seam, so
 * no Bluetooth radio is involved. Robolectric is here only for android.util.Log and a Context.
 */
@RunWith(RobolectricTestRunner::class)
class BleBearerStopRaceTest {

    /** A radio-free stand-in for `Link` (the production implementation of the same interface). */
    private class FakeLink(
        override val linkId: Long,
        override val peerId: ByteArray?,
        override val isDialer: Boolean,
    ) : BleLink {
        var closedWhy: String? = null
        val sent = mutableListOf<ByteArray>()
        override fun sendData(bytes: ByteArray) { sent += bytes }
        override fun close(why: String) { closedWhy = why }
    }

    private class FakeSink : LinkSink {
        val ups = mutableListOf<Long>()
        val bytes = mutableListOf<Long>()
        val downs = mutableListOf<Long>()
        override fun linkUp(link: Long, role: HopRole, peerId: ByteArray) { ups += link }
        override fun linkBytes(link: Long, bytes: ByteArray) { this.bytes += link }
        override fun linkDown(link: Long) { downs += link }
    }

    private fun nodeId(first: Int) = ByteArray(16).also { it[0] = first.toByte() }

    private fun bearer(): Pair<BleBearer, FakeSink> {
        val b = BleBearer(ApplicationProvider.getApplicationContext(), nodeId(0x02))
        val s = FakeSink()
        b.sink = s
        return b to s
    }

    @Test
    fun stop_closes_a_link_that_never_completed_hello() {
        val (b, sink) = bearer()
        val preHello = FakeLink(1, nodeId(0x01), isDialer = false)
        assertTrue("a running bearer adopts the link", b.adopt(preHello))
        assertEquals("an adopted link is tracked before HELLO", 1, b.trackedLinkCount())

        b.stop()
        assertEquals("stop() must close a link that never HELLO-ed", "power-off", preHello.closedWhy)

        // If its HELLO lands anyway (the frame was already in flight), it is refused, not surfaced.
        b.onUp(preHello)
        assertEquals("a stopped bearer must not surface a link", 0, sink.ups.size)
        assertEquals("bearer-stopped", preHello.closedWhy)
    }

    @Test
    fun adopt_after_stop_is_refused() {
        val (b, _) = bearer()
        b.stop()
        val late = FakeLink(9, nodeId(0x01), isDialer = true)
        assertFalse("a dial that completes after stop() must be refused", b.adopt(late))
        assertEquals("a stopped bearer tracks nothing", 0, b.trackedLinkCount())
        assertTrue(b.isStopped())
    }

    @Test
    fun close_all_reaches_pre_hello_and_established_links_alike() {
        val (b, _) = bearer()
        val established = FakeLink(1, nodeId(0x01), isDialer = true)
        val preHello = FakeLink(2, nodeId(0x03), isDialer = false)
        b.adopt(established); b.onUp(established)
        b.adopt(preHello)                          // never HELLO-ed
        assertEquals(2, b.trackedLinkCount())

        b.closeAll()                               // the BT-power-off path
        assertEquals("power-off", established.closedWhy)
        assertEquals("power-off must reach a half-open socket too", "power-off", preHello.closedWhy)
    }

    @Test
    fun a_restarted_bearer_adopts_and_surfaces_again() {
        val (b, sink) = bearer()
        b.stop()
        assertTrue(b.isStopped())
        // start() brings the radios up, which needs a real adapter; the flag is what gates adoption.
        b.markStarted()
        assertFalse(b.isStopped())

        val link = FakeLink(1, nodeId(0x01), isDialer = true)
        assertTrue(b.adopt(link))
        b.onUp(link)
        assertEquals("a restarted bearer surfaces links again", listOf(1L), sink.ups)
        assertNull(link.closedWhy)
    }

    @Test
    fun on_close_forgets_a_tracked_link() {
        val (b, _) = bearer()
        val link = FakeLink(4, nodeId(0x01), isDialer = true)
        b.adopt(link)
        b.onUp(link)
        assertEquals(1, b.trackedLinkCount())
        b.onClose(link)
        assertEquals("a closed link must not be retained by the tracker", 0, b.trackedLinkCount())
    }
}
