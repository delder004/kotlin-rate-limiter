package ratelimiter

import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

@Suppress("FunctionName")
fun CompositeRateLimiter(vararg limiters: RateLimiter): RateLimiter {
    require(limiters.isNotEmpty()) { "There must be at least one rateLimiter" }
    val refundableLimiters =
        limiters.map {
            require(it is RefundableRateLimiter) {
                "CompositeRateLimiter requires limiters that support refunds"
            }
            it
        }
    return CompositeRateLimiterImpl(refundableLimiters)
}

internal class CompositeRateLimiterImpl(
    private val limiters: List<RefundableRateLimiter>,
) : RateLimiter {
    init {
        require(limiters.isNotEmpty()) { "There must be at least one rateLimiter" }
    }

    override suspend fun acquire(permits: Int) {
        require(permits > 0) { "Permits must be positive, was $permits" }

        val acquired = mutableListOf<RefundableRateLimiter>()
        try {
            limiters.forEach { limiter ->
                limiter.acquire(permits)
                acquired.add(limiter)
            }
        } catch (e: CancellationException) {
            acquired.asReversed().forEach { it.refund(permits) }
            throw e
        }
    }

    override fun tryAcquire(permits: Int): Permit {
        require(permits > 0) { "Permits must be positive, was $permits" }

        val granted = mutableListOf<RefundableRateLimiter>()
        var maxRetryAfter = Duration.ZERO
        var denied = false

        limiters.forEach { limiter ->
            when (val permit = limiter.tryAcquire(permits)) {
                is Permit.Granted -> granted.add(limiter)
                is Permit.Denied -> {
                    denied = true
                    maxRetryAfter = maxOf(maxRetryAfter, permit.retryAfter)
                }
            }
        }

        return if (denied) {
            granted.forEach { it.refund(permits) }
            Permit.Denied(maxRetryAfter)
        } else {
            Permit.Granted
        }
    }
}
