package com.salescentral.sdk

/**
 * Outcome of one [SalesClient.flush] pass over the analytics outbox.
 *
 * A pass sends queued batches in FIFO order until the queue is empty or a
 * retryable failure stops it. Permanent rejections (validation-class 4xx)
 * drop their batch with a warning and the pass continues, so a pass that
 * delivered anything at all reports [Delivered] even if some other batch
 * was dropped along the way.
 */
sealed class FlushResult {

    /** The queue was drained; [count] items reached the server. */
    data class Delivered(val count: Int) : FlushResult()

    /**
     * The pass stopped on a retryable failure — no user token yet, a
     * transport error, a server 5xx, or a 401 — and the failed batch is
     * back at the front of the queue. Nothing sent in this pass before the
     * failure is lost; a later trigger (next enqueue, network reconnect,
     * user established, or an explicit [SalesClient.flush]) resumes.
     */
    data class Retryable(val reason: String) : FlushResult()

    /**
     * The queue was drained but NOTHING was delivered: every batch was
     * rejected permanently (validation-class 4xx) and dropped. [reason]
     * describes the last rejection.
     */
    data class Permanent(val reason: String) : FlushResult()

    /** The queue was already empty. */
    object NothingToSend : FlushResult() {
        override fun toString(): String = "NothingToSend"
    }
}
