package ratelimiter

import kotlin.time.Duration

/**
 * Creates a limiter that acquires permits from every supplied [limiters].
 *
 * A request succeeds only when all delegates can satisfy it. If one delegate
 * fails, any already-acquired permits are refunded before returning.
 *
 * @param limiters delegate limiters that all participate in each acquisition
 */
@Suppress("FunctionName")
fun CompositeRateLimiter(vararg limiters: RefundableRateLimiter): RateLimiter = CompositeRateLimiterImpl(limiters.toList())

internal class CompositeRateLimiterImpl(
    private val limiters: List<RefundableRateLimiter>,
) : RateLimiter {
    init {
        require(limiters.isNotEmpty()) { "There must be at least one rateLimiter" }
    }

    override suspend fun acquire(permits: Int) {
        require(permits > 0) { "Permits must be positive, was $permits" }
        acquireAllOrRollback(permits)
    }

    override fun tryAcquire(permits: Int): Permit {
        require(permits > 0) { "Permits must be positive, was $permits" }
        return tryAcquireAllOrRollback(permits)
    }

    private suspend fun acquireAllOrRollback(permits: Int) {
        val acquired = mutableListOf<RefundableRateLimiter>()
        try {
            limiters.forEach { limiter ->
                limiter.acquire(permits)
                acquired.add(limiter)
            }
        } catch (e: Throwable) {
            rollback(acquired, permits)
            throw e
        }
    }

    private fun tryAcquireAllOrRollback(permits: Int): Permit {
        val granted = mutableListOf<RefundableRateLimiter>()

        limiters.forEachIndexed { index, limiter ->
            when (val permit = limiter.tryAcquire(permits)) {
                is Permit.Granted -> granted.add(limiter)
                is Permit.Denied -> {
                    rollback(granted, permits)
                    return collectRetryAfterFromRemaining(
                        remaining = limiters.drop(index + 1),
                        permits = permits,
                        initialRetryAfter = permit.retryAfter,
                    )
                }
            }
        }

        return Permit.Granted
    }

    private fun collectRetryAfterFromRemaining(
        remaining: List<RefundableRateLimiter>,
        permits: Int,
        initialRetryAfter: Duration,
    ): Permit.Denied {
        var maxRetryAfter = initialRetryAfter
        remaining.forEach { limiter ->
            when (val permit = limiter.tryAcquire(permits)) {
                is Permit.Granted -> limiter.refund(permits)
                is Permit.Denied -> maxRetryAfter = maxOf(maxRetryAfter, permit.retryAfter)
            }
        }
        return Permit.Denied(maxRetryAfter)
    }

    private fun rollback(
        acquired: List<RefundableRateLimiter>,
        permits: Int,
    ) {
        acquired.asReversed().forEach { it.refund(permits) }
    }
}
