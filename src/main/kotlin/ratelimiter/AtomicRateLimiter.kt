package ratelimiter

import kotlinx.coroutines.delay
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.updateAndFetch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

@OptIn(ExperimentalAtomicApi::class)
internal abstract class AtomicRateLimiter(
    protected val config: BucketConfig,
    initialBucket: PermitBucket,
) : RefundableRateLimiter {
    private val bucketState = AtomicReference(initialBucket)

    private data class AcquireTransition(
        val current: PermitBucket,
        val refilled: PermitBucket,
        val next: PermitBucket,
        val waitFor: Duration,
        // Exact warmup progress added by this acquisition. Carried so the
        // cancellation slow path can invert the consume more faithfully
        // without reconstructing it from the already-advanced bucket.
        val warmupDelta: Double,
    )

    protected abstract fun waitDuration(
        refilled: PermitBucket,
        next: PermitBucket,
    ): Duration

    override suspend fun acquire(permits: Int) {
        require(permits > 0) { "Permits must be positive, was $permits" }

        while (true) {
            val t = computeAcquireTransition(permits)

            // Publish the reservation atomically *before* waiting. Concurrent
            // callers already see the consumed permits, so their wait times
            // stack correctly; a cancellation during delay refunds via the
            // CAS loop below.
            if (bucketState.compareAndSet(t.current, t.next)) {
                return try {
                    delay(t.waitFor)
                } catch (e: CancellationException) {
                    // Prefer an exact rewind: if no other caller has touched
                    // bucketState since our CAS, the state is still t.next and
                    // we can replace it with t.current refilled to now — fully
                    // "as if this acquire never happened", with the elapsed
                    // delay counted as ordinary idle time against the
                    // pre-acquire state (no phantom debt repayment, no warmer
                    // interval). If another caller has advanced the state,
                    // fall back to a best-effort refund: on a Warming bucket
                    // we can subtract the exact warmup delta we recorded at
                    // transition time; on a Stable bucket there is no warmup
                    // bookkeeping and a plain refund is trivially exact.
                    bucketState.updateAndFetch { bucket ->
                        if (bucket === t.next) {
                            t.current.refill(config)
                        } else {
                            when (bucket) {
                                is PermitBucket.Stable -> bucket.refund(permits, config)
                                is PermitBucket.Warming -> bucket.refundCancelled(permits, t.warmupDelta, config)
                            }
                        }
                    }
                    throw e
                }
            }
        }
    }

    override fun tryAcquire(permits: Int): Permit {
        require(permits > 0) { "Permits must be positive, was $permits" }

        while (true) {
            val t = computeAcquireTransition(permits)

            if (t.waitFor == Duration.ZERO) {
                if (bucketState.compareAndSet(t.current, t.next)) {
                    return Permit.Granted
                }
                continue
            }

            // Denial CASes to `refilled`, not `next`: we want to publish any
            // refill/cooldown progress observed during this attempt, but must
            // not reserve future permits for a caller that isn't going to wait.
            if (bucketState.compareAndSet(t.current, t.refilled)) {
                return Permit.Denied(retryAfter = t.waitFor)
            }
        }
    }

    override fun refund(permits: Int) {
        require(permits > 0) { "Permits must be positive, was $permits" }

        while (true) {
            val current = bucketState.load()
            val next = current.refund(permits, config)
            if (bucketState.compareAndSet(current, next)) return
        }
    }

    private fun computeAcquireTransition(permits: Int): AcquireTransition {
        val current = bucketState.load()
        val refilled = current.refill(config)
        val consumed = refilled.consume(permits, config)
        return AcquireTransition(
            current = current,
            refilled = refilled,
            next = consumed.bucket,
            waitFor = waitDuration(refilled, consumed.bucket),
            warmupDelta = consumed.warmupDelta,
        )
    }
}
