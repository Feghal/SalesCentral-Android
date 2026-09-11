package com.salescentral.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Pure data-structure tests — mirrors the Swift SDK's OutboxTests. */
class OutboxTest {

    private val t0: Instant = Instant.parse("2026-09-11T10:00:00Z")

    private fun event(i: Int) = OutboxItem.Event("e$i", emptyMap(), t0.plusSeconds(i.toLong()))
    private fun session(i: Int) = OutboxItem.Session(t0, t0.plusSeconds(60L + i), 60 + i)

    private fun names(batch: OutboxBatch?): List<String> =
        (batch as OutboxBatch.Events).events.map { it.name }

    @Test
    fun `append and drainNext preserve FIFO order`() {
        val outbox = Outbox()
        outbox.append(listOf(event(1), event(2)))
        outbox.append(listOf(event(3)))
        assertEquals(3, outbox.count)

        assertEquals(listOf("e1", "e2", "e3"), names(outbox.drainNext()))
        assertNull(outbox.drainNext())
        assertTrue(outbox.isEmpty)
    }

    @Test
    fun `cap 500 drops the oldest on append and reports how many`() {
        val outbox = Outbox()
        assertEquals(0, outbox.append((0 until 500).map(::event)))
        assertEquals(1, outbox.append(listOf(event(500))))
        assertEquals(Outbox.CAP, outbox.count)

        val snapshot = outbox.snapshot()
        assertEquals("e1", (snapshot.first() as OutboxItem.Event).name)
        assertEquals("e500", (snapshot.last() as OutboxItem.Event).name)
    }

    @Test
    fun `drainNext chunks event runs at 50 and isolates sessions`() {
        val outbox = Outbox()
        outbox.append((0 until 60).map(::event))
        outbox.append(listOf(session(1)))
        outbox.append((60 until 65).map(::event))

        val first = outbox.drainNext() as OutboxBatch.Events
        assertEquals(Outbox.EVENT_CHUNK, first.events.size)
        assertEquals("e0", first.events.first().name)
        assertEquals("e49", first.events.last().name)

        assertEquals((50 until 60).map { "e$it" }, names(outbox.drainNext()))

        val third = outbox.drainNext()
        assertTrue(third is OutboxBatch.Session)
        assertEquals(session(1), (third as OutboxBatch.Session).session)

        assertEquals((60 until 65).map { "e$it" }, names(outbox.drainNext()))
        assertNull(outbox.drainNext())
    }

    @Test
    fun `requeue restores the failed batch at the front ahead of later appends`() {
        val outbox = Outbox()
        outbox.append(listOf(event(1), event(2)))
        val batch = outbox.drainNext()!!
        outbox.append(listOf(event(3))) // arrived while the batch was in flight

        assertEquals(0, outbox.requeue(batch))

        assertEquals(listOf("e1", "e2", "e3"), names(outbox.drainNext()))
    }

    @Test
    fun `requeue over the cap drops the oldest of the requeued batch itself`() {
        val outbox = Outbox(cap = 3)
        outbox.append(listOf(event(1), event(2), event(3)))
        val batch = outbox.drainNext()!!
        outbox.append(listOf(event(4), event(5)))

        assertEquals(2, outbox.requeue(batch))

        assertEquals(listOf("e3", "e4", "e5"), names(outbox.drainNext()))
    }

    @Test
    fun `removeAll empties the queue`() {
        val outbox = Outbox()
        outbox.append(listOf(event(1), session(1)))
        outbox.removeAll()
        assertTrue(outbox.isEmpty)
        assertNull(outbox.drainNext())
    }
}
