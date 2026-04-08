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

    protected abstract fun waitDuration(
        refilled: PermitBucket,
        next: PermitBucket,
    ): Duration

    override suspend fun acquire(permits: Int) {
        require(permits > 0) { "Permits must be positive, was $permits" }

        while (true) {
            val current = bucketState.load()
            val refilled = current.refill(config)
            val next = refilled.consume(permits, config)
            val waitDuration = waitDuration(refilled, next)

            if (bucketState.compareAndSet(current, next)) {
                return try {
                    delay(waitDuration)
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
            val current = bucketState.load()
            val refilled = current.refill(config)
            val next = refilled.consume(permits, config)
            val waitDuration = waitDuration(refilled, next)

            if (waitDuration == Duration.ZERO) {
                if (bucketState.compareAndSet(current, next)) {
                    return Permit.Granted
                }
                continue
            }

            if (bucketState.compareAndSet(current, refilled)) {
                return Permit.Denied(retryAfter = waitDuration)
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
}
