package com.salescentral.sdk

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ProductsLoader] (SDK 1.4.1) — the caching decision behind
 * `SalesCentral.loadProducts()` / `start()`'s prefetch / `reloadProducts()`.
 * The facade itself cannot be driven from a JVM test (`configure()` builds a
 * real Play `BillingClient`), so the policy is exercised here through the
 * loader's two injected functions: the SKU list the client knows, and the
 * fetch that `fetchProductsFromPlay` performs.
 *
 * The scenario that motivated it (phase-review C2): a paywall opened before
 * bootstrap called `loadProducts()` while the SKU list was still empty, and
 * the empty answer was cached for the life of the process — every later
 * paywall stayed "Unavailable" until relaunch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProductsLoaderTest {

    /** Scripted fetch: counts calls, records the flag, resolves the ids known at call time. */
    private class Fetch(var ids: List<String> = emptyList()) {
        var calls = 0
        val forceFlags = mutableListOf<Boolean>()
        var failWith: Exception? = null
        var emptyOnce = false
        var gate: CompletableDeferred<Unit>? = null

        suspend fun run(forceRefreshIds: Boolean): List<String> {
            calls++
            forceFlags += forceRefreshIds
            gate?.await()
            failWith?.let { throw it }
            if (emptyOnce) {
                emptyOnce = false
                return emptyList()
            }
            return ids.map { it.uppercase() }
        }
    }

    private fun runTestWithLoader(block: suspend TestScope.(Fetch, ProductsLoader<String>) -> Unit) =
        runTest {
            val fetch = Fetch()
            // A supervised scope on the test scheduler: a failed fetch must not
            // fail the test's own scope, only the job that awaited it.
            val loader = ProductsLoader<String>(
                TestFixtures.scheduledScope(testScheduler),
                configuredIds = { fetch.ids },
                fetch = fetch::run,
            )
            block(fetch, loader)
        }

    // ------------------------------------------------------------------
    // C2: the pre-bootstrap call
    // ------------------------------------------------------------------

    @Test
    fun `load before the SKU list is known answers empty, fetches nothing and caches nothing`() =
        runTestWithLoader { fetch, loader ->
            assertEquals(emptyList<String>(), loader.load())
            assertEquals("no Play round-trip without SKUs", 0, fetch.calls)
            assertFalse("the empty answer is not retained", loader.hasTask)
        }

    @Test
    fun `after bootstrap the next load fetches with the configured ids`() =
        runTestWithLoader { fetch, loader ->
            assertEquals(emptyList<String>(), loader.load())
            fetch.ids = listOf("tokens_500", "pro_monthly") // the bootstrap bundle landed

            assertEquals(listOf("TOKENS_500", "PRO_MONTHLY"), loader.load())
            assertEquals(1, fetch.calls)
            assertEquals(listOf(false), fetch.forceFlags)
            assertTrue("a fetch WITH products is retained", loader.hasTask)
            assertEquals("…and served from the cache", listOf("TOKENS_500", "PRO_MONTHLY"), loader.load())
            assertEquals(1, fetch.calls)
        }

    @Test
    fun `start's prefetch after a pre-bootstrap load fetches for real and load shares it`() =
        runTestWithLoader { fetch, loader ->
            assertEquals(emptyList<String>(), loader.load()) // paywall opened too early
            fetch.ids = listOf("tokens_500")

            loader.prefetch() // what start() does once bootstrap lands
            assertTrue(loader.hasTask)
            assertEquals(listOf("TOKENS_500"), loader.load())
            assertEquals("load awaited the prefetch instead of starting its own", 1, fetch.calls)
        }

    // ------------------------------------------------------------------
    // Retention rule: failed / empty jobs evict themselves
    // ------------------------------------------------------------------

    @Test
    fun `a failed load is not retained and the next load retries`() =
        runTestWithLoader { fetch, loader ->
            fetch.ids = listOf("tokens_500")
            fetch.failWith = SalesError.Network("Play Billing connection failed")

            val err = runCatching { loader.load() }.exceptionOrNull()
            assertTrue(err is SalesError.Network)
            assertFalse("the failure evicted itself", loader.hasTask)

            fetch.failWith = null
            assertEquals(listOf("TOKENS_500"), loader.load())
            assertEquals(2, fetch.calls)
        }

    @Test
    fun `a failed prefetch nobody awaited is not retained either`() =
        runTestWithLoader { fetch, loader ->
            fetch.ids = listOf("tokens_500")
            fetch.failWith = SalesError.Network("offline")
            loader.prefetch()
            advanceUntilIdle()
            assertFalse(loader.hasTask)

            fetch.failWith = null
            assertEquals(listOf("TOKENS_500"), loader.load())
            assertEquals(2, fetch.calls)
        }

    @Test
    fun `an empty result with SKUs known is not retained`() =
        runTestWithLoader { fetch, loader ->
            fetch.ids = listOf("tokens_500")
            fetch.emptyOnce = true // Play recognised none of the SKUs (misregistered in Play Console)

            assertEquals(emptyList<String>(), loader.load())
            assertEquals(1, fetch.calls)
            assertFalse("Play answered, but with nothing usable", loader.hasTask)
            assertEquals("fixed in Play Console — picked up without a relaunch", listOf("TOKENS_500"), loader.load())
            assertEquals(2, fetch.calls)
            assertTrue(loader.hasTask)
        }

    @Test
    fun `prefetch keeps a retained result and an in-flight job`() =
        runTestWithLoader { fetch, loader ->
            fetch.ids = listOf("tokens_500")
            assertEquals(listOf("TOKENS_500"), loader.load())
            loader.prefetch()
            advanceUntilIdle()
            assertEquals("retained products are not refetched", 1, fetch.calls)

            loader.reset()
            fetch.gate = CompletableDeferred()
            loader.prefetch()
            loader.prefetch()
            advanceUntilIdle()
            assertEquals("one job in flight, shared", 2, fetch.calls)
            fetch.gate?.complete(Unit)
            assertEquals(listOf("TOKENS_500"), loader.load())
            assertEquals(2, fetch.calls)
        }

    // ------------------------------------------------------------------
    // Single flight / reload / reset
    // ------------------------------------------------------------------

    @Test
    fun `concurrent loads share one in-flight job`() =
        runTestWithLoader { fetch, loader ->
            fetch.ids = listOf("tokens_500")
            fetch.gate = CompletableDeferred()

            val a = async { loader.load() }
            val b = async { loader.load() }
            advanceUntilIdle()
            assertEquals("both callers parked on the same fetch", 1, fetch.calls)

            fetch.gate?.complete(Unit)
            assertEquals(listOf("TOKENS_500"), a.await())
            assertEquals(listOf("TOKENS_500"), b.await())
            assertEquals(1, fetch.calls)
        }

    @Test
    fun `reload replaces a retained result and forces the SKU refresh`() =
        runTestWithLoader { fetch, loader ->
            fetch.ids = listOf("tokens_500")
            assertEquals(listOf("TOKENS_500"), loader.load())

            fetch.ids = listOf("tokens_500", "tokens_2000") // admin added a product
            assertEquals(listOf("TOKENS_500", "TOKENS_2000"), loader.reload())
            assertEquals(listOf(false, true), fetch.forceFlags)
            assertEquals("the reloaded list is what load now serves", listOf("TOKENS_500", "TOKENS_2000"), loader.load())
            assertEquals(2, fetch.calls)
        }

    @Test
    fun `a failed reload is not retained over the previous result`() =
        runTestWithLoader { fetch, loader ->
            fetch.ids = listOf("tokens_500")
            assertEquals(listOf("TOKENS_500"), loader.load())

            fetch.failWith = SalesError.Network("offline")
            assertTrue(runCatching { loader.reload() }.exceptionOrNull() is SalesError.Network)
            assertFalse("neither the stale result nor the failure is kept", loader.hasTask)

            fetch.failWith = null
            assertEquals(listOf("TOKENS_500"), loader.load())
            assertEquals(3, fetch.calls)
        }

    @Test
    fun `reset drops the retained job`() =
        runTestWithLoader { fetch, loader ->
            fetch.ids = listOf("tokens_500")
            assertEquals(listOf("TOKENS_500"), loader.load())
            loader.reset()
            assertFalse(loader.hasTask)
            assertEquals(listOf("TOKENS_500"), loader.load())
            assertEquals(2, fetch.calls)
        }
}
