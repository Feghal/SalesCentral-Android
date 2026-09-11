package com.salescentral.sdk

import java.time.Instant

/**
 * One queued analytics call, carrying its original wall-clock data so a
 * late flush is indistinguishable server-side from a timely send (the
 * server honors client `occurredAt` / `startedAt` / `endedAt`).
 *
 * Port of `OutboxItem` in the Swift SDK (`Outbox.swift`).
 */
internal sealed class OutboxItem {
    data class Event(
        val name: String,
        val properties: Map<String, Any?>,
        val occurredAt: Instant,
    ) : OutboxItem()

    data class Session(
        val start: Instant,
        val end: Instant,
        val durationSec: Int?,
    ) : OutboxItem()
}

/**
 * One unit of flush work drained from the outbox: a batch of events
 * (≤ [Outbox.EVENT_CHUNK], sent as one recordEvent batch body) or a single
 * session (recordSession has no batch form).
 */
internal sealed class OutboxBatch {
    data class Events(val events: List<OutboxItem.Event>) : OutboxBatch()
    data class Session(val session: OutboxItem.Session) : OutboxBatch()

    val items: List<OutboxItem>
        get() = when (this) {
            is Events -> events
            is Session -> listOf(session)
        }
}

/**
 * In-memory FIFO for analytics calls that haven't been sent yet — because
 * there is no user token, the device is offline, or the server answered
 * 5xx. Port of the Swift SDK's `Outbox` struct with the same policy:
 *
 *  - capped at [CAP] items; appending past the cap drops the OLDEST items
 *    (recency wins), and so does re-queueing a failed batch;
 *  - [drainNext] hands out work in FIFO order, chunking runs of events at
 *    [EVENT_CHUNK] (the server's `MAX_BATCH`) and sessions one at a time;
 *  - [requeue] puts a failed batch back at the FRONT, restoring the
 *    pre-drain order ahead of anything appended meanwhile.
 *
 * Unlike the Swift version (a value type isolated inside the `SalesClient`
 * actor) this one is self-synchronising: producers (`track` from any
 * thread, the session tracker) and the single drain coroutine touch it
 * concurrently, so every operation takes [lock]. No I/O happens here —
 * [SalesClient] owns the instance and performs all sends, holding NO lock
 * while a request is in flight.
 */
internal class Outbox(private val cap: Int = CAP) {

    companion object {
        /** Total queued items. */
        const val CAP = 500

        /** Server's MAX_BATCH for recordEvent. */
        const val EVENT_CHUNK = 50
    }

    private val lock = Any()
    private val items = ArrayDeque<OutboxItem>()

    val isEmpty: Boolean get() = synchronized(lock) { items.isEmpty() }
    val count: Int get() = synchronized(lock) { items.size }

    /** Snapshot of the queue in FIFO order (tests / diagnostics). */
    fun snapshot(): List<OutboxItem> = synchronized(lock) { items.toList() }

    /**
     * Append preserving call order. When the result would exceed the cap,
     * the OLDEST items are dropped. Returns how many were dropped so the
     * caller can log the loss.
     */
    fun append(newItems: List<OutboxItem>): Int = synchronized(lock) {
        items.addAll(newItems)
        trimToCap()
    }

    /**
     * Next unit of work in FIFO order: the leading run of events (at most
     * [EVENT_CHUNK] of them) as one batch, or the single leading session.
     * Removes the returned items — the caller holds them while the request
     * is in flight and [requeue]s them on a retryable failure. Null when empty.
     */
    fun drainNext(): OutboxBatch? = synchronized(lock) {
        val first = items.firstOrNull() ?: return null
        if (first is OutboxItem.Session) {
            items.removeFirst()
            return OutboxBatch.Session(first)
        }
        val run = ArrayList<OutboxItem.Event>(minOf(EVENT_CHUNK, items.size))
        while (run.size < EVENT_CHUNK) {
            val next = items.firstOrNull() as? OutboxItem.Event ?: break
            run += next
            items.removeFirst()
        }
        OutboxBatch.Events(run)
    }

    /**
     * Put a failed batch back at the FRONT, restoring the pre-drain order.
     * The cap holds here too: if re-inserting would exceed it, the OLDEST
     * items — the head of the requeued batch itself — are dropped (recency
     * wins, same policy as [append]). Returns the dropped count.
     */
    fun requeue(batch: OutboxBatch): Int = synchronized(lock) {
        val batchItems = batch.items
        for (i in batchItems.indices.reversed()) items.addFirst(batchItems[i])
        trimToCap()
    }

    fun removeAll() = synchronized(lock) { items.clear() }

    /** Must be called with [lock] held. Returns the number of items dropped. */
    private fun trimToCap(): Int {
        val overflow = items.size - cap
        if (overflow <= 0) return 0
        repeat(overflow) { items.removeFirst() }
        return overflow
    }
}
