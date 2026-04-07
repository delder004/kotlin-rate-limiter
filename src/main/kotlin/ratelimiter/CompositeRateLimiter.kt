package ratelimiter

import kotlin.math.max


@Suppress("FunctionName")
fun CompositeRateLimiter(
    vararg limiters: RateLimiter,
): RateLimiter = CompositeRateLimiterImpl(limiters.toList())


internal class CompositeRateLimiterImpl(
    private val limiters: List<RateLimiter>
) : RateLimiter {

    override suspend fun acquire(permits: Int) {
        limiters.forEach { it.acquire(permits) }
    }

    override fun tryAcquire(permits: Int): Permit {
        return limiters.map { it.tryAcquire(permits) }.reduce {
            acc, permit -> when {
                (acc is Permit.Denied && permit is Permit.Denied) ->
                    Permit.Denied(maxOf(acc.retryAfter, permit.retryAfter))
                (acc is Permit.Granted && permit is Permit.Denied) -> permit
                else -> acc
            }
        }

    }
}