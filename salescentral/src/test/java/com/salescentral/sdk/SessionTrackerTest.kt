package com.salescentral.sdk

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * [SessionTracker.start] (SDK 1.4.1): the double-start guard is
 * synchronised, so two bootstraps racing each other (the SDK's own and an
 * app's defensive one) register ONE process-lifecycle observer — two would
 * record every session twice.
 *
 * `Looper.getMainLooper()` is null on the JVM (`isReturnDefaultValues`), so
 * the tracker's main-thread hop runs inline and the fake lifecycle sees the
 * registration synchronously.
 */
class SessionTrackerTest {

    /** Counts registrations; the small sleep widens the old race's window so an unguarded start would lose. */
    private class CountingLifecycle : Lifecycle() {
        val added = AtomicInteger()
        val removed = AtomicInteger()

        override fun addObserver(observer: LifecycleObserver) {
            Thread.sleep(20)
            added.incrementAndGet()
        }

        override fun removeObserver(observer: LifecycleObserver) {
            removed.incrementAndGet()
        }

        override val currentState: State get() = State.CREATED
    }

    private fun tracker(lifecycle: CountingLifecycle): SessionTracker =
        SessionTracker(TestFixtures.client(FakeTransport()), lifecycleProvider = { lifecycle })

    @Test
    fun `concurrent start calls register exactly one observer`() {
        val lifecycle = CountingLifecycle()
        val tracker = tracker(lifecycle)
        val workers = 8
        val ready = CountDownLatch(workers)
        val go = CountDownLatch(1)
        val threads = List(workers) {
            thread {
                ready.countDown()
                go.await()
                tracker.start()
            }
        }
        ready.await()
        go.countDown()
        threads.forEach { it.join() }

        assertEquals(1, lifecycle.added.get())
        tracker.start()
        assertEquals("still idempotent afterwards", 1, lifecycle.added.get())
    }

    @Test
    fun `stop removes the observer and start may register again`() {
        val lifecycle = CountingLifecycle()
        val tracker = tracker(lifecycle)
        tracker.start()
        tracker.stop()
        tracker.stop()
        assertEquals(1, lifecycle.removed.get())

        tracker.start()
        assertEquals(2, lifecycle.added.get())
    }

    @Test
    fun `a lifecycle that is unavailable leaves start retryable`() {
        val lifecycle = CountingLifecycle()
        var available = false
        val tracker = SessionTracker(TestFixtures.client(FakeTransport())) {
            if (!available) throw IllegalStateException("no process lifecycle") else lifecycle
        }
        tracker.start()
        assertEquals(0, lifecycle.added.get())

        available = true
        tracker.start()
        assertEquals(1, lifecycle.added.get())
    }
}
