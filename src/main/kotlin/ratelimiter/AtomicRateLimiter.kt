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
            // stack correctly; a cancellation during delay refunds via a
            // separate CAS loop below.
            if (bucketState.compareAndSet(t.current, t.next)) {
                return try {
                    delay(t.waitFor)
                } catch (e: CancellationException) {
                    bucketState.updateAndFetch { bucket -> bucket.refund(permits, config) }
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
        val next = refilled.consume(permits, config)
        return AcquireTransition(
            current = current,
            refilled = refilled,
            next = next,
            waitFor = waitDuration(refilled, next),
        )
    }
}
