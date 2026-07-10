package sh.hopme.bearers.relay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PART-A defect 1: the RelayBearer's reconnect executor + OkHttp resources must be released on stop()
 * (they were leaked on every disable/enable). These are radio-free: constructing a RelayBearer only
 * builds an OkHttpClient + a single-thread scheduled executor + a SHA-256 peer id (all plain JVM, no
 * android.util.Log until start()/dial()), so we can drive the lifecycle without a device.
 *
 * We assert the leak fix through the [RelayBearer.isTornDown] hook (exec.isShutdown) rather than by
 * counting threads, that a stopped bearer is RESTARTABLE (start() reinstalls a fresh executor rather
 * than being a permanent no-op), and that a stopped bearer degrades gracefully: send() can't crash.
 */
class RelayBearerLifecycleTest {

    private fun newBearer() = RelayBearer("wss://relay.example.test/")

    @Test fun freshBearerIsNotTornDown() {
        val b = newBearer()
        assertFalse("a bearer that was never stopped must still be live", b.isTornDown)
    }

    @Test fun stopShutsDownTheReconnectExecutor() {
        // The core of the leak fix: stop() must shut the single-thread executor down so its
        // "hop.relay.bearer" thread is released instead of leaking on every disable/enable.
        val b = newBearer()
        b.stop()
        assertTrue("stop() must shut the reconnect executor down (no leaked thread)", b.isTornDown)
    }

    @Test fun stopIsIdempotent() {
        // A double stop() (e.g. BearerManager.stop then a service teardown) must not throw.
        val b = newBearer()
        b.stop()
        b.stop()
        assertTrue(b.isTornDown)
    }

    @Test fun startAfterStopReinstallsAFreshExecutor() {
        // r5 fix: a stopped bearer is RESTARTABLE. Pre-r5, stop() shut the executor down permanently
        // so start() after stop() was a dead no-op (and in-flight OkHttp callbacks threw
        // RejectedExecutionException onto the shut exec). start() must now install a fresh executor and
        // bring the bearer back to life, without throwing.
        val b = newBearer()
        b.stop()
        assertTrue("torn down while stopped", b.isTornDown)
        b.start()   // must not throw; reinstalls a live executor
        assertFalse("start() after stop() brings the bearer back to life (fresh executor)", b.isTornDown)
        b.stop()    // clean up the restarted bearer
    }

    @Test fun sendAfterStopDoesNotThrow() {
        // No live socket after stop(); a stray send() on the dead link id must be a safe no-op.
        val b = newBearer()
        b.stop()
        b.send(byteArrayOf(1, 2, 3), 1L)   // must not throw (ws is null)
    }

    @Test fun sendOnAWrongLinkIdIsIgnored() {
        // The RelayBearer owns exactly one link id; a send on any other id is dropped without touching
        // the socket. (Also proves send() before start() is safe.)
        val b = newBearer()
        b.send(byteArrayOf(9), 999L)
        assertFalse(b.isTornDown)
        b.stop()
    }
}
