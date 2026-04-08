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

    private data class Attempt(
        val current: PermitBucket,
        val refilled: PermitBucket,
        val next: PermitBucket,
        val delay: Duration,
    )

    protected abstract fun waitDuration(
        refilled: PermitBucket,
        next: PermitBucket,
    ): Duration

    override suspend fun acquire(permits: Int) {
        require(permits > 0) { "Permits must be positive, was $permits" }

        while (true) {
            val attempt = nextAttempt(permits)

            if (bucketState.compareAndSet(attempt.current, attempt.next)) {
                return try {
                    delay(attempt.delay)
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
            val attempt = nextAttempt(permits)

            if (attempt.delay == Duration.ZERO) {
                if (bucketState.compareAndSet(attempt.current, attempt.next)) {
                    return Permit.Granted
                }
                continue
            }

            if (bucketState.compareAndSet(attempt.current, attempt.refilled)) {
                return Permit.Denied(retryAfter = attempt.delay)
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

    private fun nextAttempt(permits: Int): Attempt {
        val current = bucketState.load()
        val refilled = current.refill(config)
        val next = refilled.consume(permits, config)
        return Attempt(
            current = current,
            refilled = refilled,
            next = next,
            delay = waitDuration(refilled, next),
        )
    }
}
