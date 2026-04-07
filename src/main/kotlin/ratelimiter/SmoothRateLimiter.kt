package ratelimiter

import kotlinx.coroutines.delay
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.updateAndFetch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.TimeSource

@Suppress("FunctionName")
fun SmoothRateLimiter(
    permits: Int,
    per: Duration,
    warmup: Duration = Duration.ZERO,
    timeSource: TimeSource = TimeSource.Monotonic,
): RateLimiter = SmoothRateLimiterImpl(permits, per, warmup, timeSource)

@OptIn(ExperimentalAtomicApi::class)
internal class SmoothRateLimiterImpl(
    private val permits: Int,
    private val period: Duration,
    private val warmup: Duration,
    private val timeSource: TimeSource,
) : RateLimiter {
    init {
        require(permits > 0) { "Permits must be positive, was $permits" }
        require(period > Duration.ZERO) { "Period must be positive, was $period" }
        require(warmup >= Duration.ZERO) { "Warmup can't be negative, was $warmup" }
    }

    private val bucketState =
        AtomicReference(
            PermitBucket(
                available = 1.0,
                capacity = 1.0,
                timeSource = timeSource,
                refilledAt = timeSource.markNow(),
                refillInterval = period / permits,
            ),
        )

    override suspend fun acquire(permits: Int) {
        require(permits > 0) { "Permits must be positive, was $permits" }

        while (true) {
            val current = bucketState.load()
            val next = current.remove(permits)
            val deficit = if (next.available < 0) next.available else 0.0
            val waitDuration = next.refillInterval * -deficit

            if (bucketState.compareAndSet(current, next)) {
                return try {
                    delay(waitDuration)
                } catch (e: CancellationException) {
                    bucketState.updateAndFetch { bucket -> bucket.replace(permits) }
                    throw e
                }
            }
            // CAS failed — another coroutine updated state, retry
        }
    }

    override fun tryAcquire(permits: Int): Permit {
        require(permits > 0) { "Permits must be positive, was $permits" }

        while (true) {
            val current = bucketState.load()
            val next = current.remove(permits)
            val deficit = if (next.available < 0) next.available else 0.0
            val waitDuration = next.refillInterval * -deficit

            if (waitDuration == Duration.ZERO) {
                if (bucketState.compareAndSet(current, next)) {
                    return Permit.Granted
                }
                // CAS failed — another coroutine updated state, retry
            } else {
                return Permit.Denied(retryAfter = waitDuration)
            }
        }
    }
}
