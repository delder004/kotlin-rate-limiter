package ratelimiter

@Suppress("FunctionName")
fun CompositeRateLimiter(vararg limiters: RateLimiter): RateLimiter = CompositeRateLimiterImpl(limiters.toList())

internal class CompositeRateLimiterImpl(
    private val limiters: List<RateLimiter>,
) : RateLimiter {
    init {
        require(limiters.isNotEmpty()) { "There must be at least one rateLimiter" }
    }

    override suspend fun acquire(permits: Int) {
        require(permits > 0) { "Permits must be positive, was $permits" }

        limiters.forEach { it.acquire(permits) }
    }

    override fun tryAcquire(permits: Int): Permit {
        require(permits > 0) { "Permits must be positive, was $permits" }

        return limiters.map { it.tryAcquire(permits) }.reduce { acc, permit ->
            when {
                (acc is Permit.Denied && permit is Permit.Denied) ->
                    Permit.Denied(maxOf(acc.retryAfter, permit.retryAfter))
                (acc is Permit.Granted && permit is Permit.Denied) -> permit
                else -> acc
            }
        }
    }
}
