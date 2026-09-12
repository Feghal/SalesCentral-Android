package com.salescentral.sdk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlin.coroutines.coroutineContext

/**
 * Single-flight holder for the facade's Play product list — the state behind
 * [SalesCentral.loadProducts] / [SalesCentral.reloadProducts] and the
 * prefetch [SalesCentral.start] kicks off after bootstrap. Concurrent callers
 * await ONE in-flight job.
 *
 * Retention rule (1.4.1): a finished job is kept only when it succeeded AND
 * returned at least one product. A job that failed (Play unreachable) or came
 * back empty evicts itself as it finishes — before any awaiter resumes — so
 * the next [load] / [prefetch] fetches again instead of replaying a stale
 * answer. [load] also never starts a job while [configuredIds] is empty:
 * before bootstrap has delivered the registered SKUs (or when the admin
 * registered none) it answers an empty list and caches nothing. Before 1.4.1
 * the first job was kept for the life of the process whatever it produced, so
 * a [load] that ran before bootstrap pinned an empty list and every later
 * paywall stayed empty until relaunch.
 *
 * The facade cannot be driven from a JVM test (`SalesCentral.configure` builds
 * a real Play `BillingClient`), so the whole caching decision lives here,
 * behind two injected functions. [T] is generic only so the tests can run the
 * policy on plain values — Play's `ProductDetails` cannot be constructed
 * off-device; the facade uses `ProductsLoader<ProductDetails>`.
 */
internal class ProductsLoader<T : Any>(
    private val scope: CoroutineScope,
    /** The SKUs the client currently knows — empty until a config bundle has landed. */
    private val configuredIds: () -> List<String>,
    /** The fetch itself; `forceRefreshIds` re-runs `ensureUser` first (see [reload]). */
    private val fetch: suspend (forceRefreshIds: Boolean) -> List<T>,
) {

    private val lock = Any()

    /** In flight, or finished with products. Never a failed or empty job (they evict themselves). */
    private var task: Deferred<List<T>>? = null

    /** True while a job is in flight or a non-empty result is retained. */
    val hasTask: Boolean get() = synchronized(lock) { task != null }

    /**
     * Await the in-flight / retained job, starting one when there is none —
     * unless no SKU is known yet, in which case the answer is an empty list
     * that is NOT cached, so the first call after bootstrap fetches for real.
     */
    suspend fun load(): List<T> {
        val job = synchronized(lock) {
            task ?: if (configuredIds().isEmpty()) {
                null
            } else {
                SalesLog.debug(SalesLog.Category.STORE, "loadProducts() — no prefetch in flight, fetching on demand")
                start(forceRefreshIds = false)
            }
        }
        if (job == null) {
            SalesLog.debug(
                SalesLog.Category.STORE,
                "loadProducts() — no SKUs known yet (bootstrap pending, or none registered); empty, not cached",
            )
            return emptyList()
        }
        return job.await()
    }

    /**
     * Start the post-bootstrap prefetch without awaiting it. A job already in
     * flight (or retained with products) is kept and shared; a job that
     * failed or finished empty is already gone by now (see [start]), so this
     * always replaces what a pre-bootstrap or offline attempt left behind.
     */
    fun prefetch() {
        synchronized(lock) {
            if (task == null) start(forceRefreshIds = false)
        }
    }

    /** Force a fresh job (replacing whatever is retained) and await it. */
    suspend fun reload(): List<T> = synchronized(lock) { start(forceRefreshIds = true) }.await()

    /** Cancel and drop the current job ([SalesCentral.reset]). */
    fun reset() {
        synchronized(lock) {
            task?.cancel()
            task = null
        }
    }

    /**
     * Start a job and make it the current one. Must be called under [lock].
     * The job is created LAZY so it cannot run — and so cannot evict itself
     * — before it has been assigned to [task]; the `finally` then drops it
     * again the moment it finishes without products, but only if it is
     * still the current job (a [reload] may have replaced it meanwhile).
     */
    private fun start(forceRefreshIds: Boolean): Deferred<List<T>> {
        val job = scope.async(start = CoroutineStart.LAZY) {
            val self = coroutineContext[Job]
            var retain = false
            try {
                fetch(forceRefreshIds).also { retain = it.isNotEmpty() }
            } finally {
                if (!retain) synchronized(lock) { if (task === self) task = null }
            }
        }
        task = job
        job.start()
        return job
    }
}
