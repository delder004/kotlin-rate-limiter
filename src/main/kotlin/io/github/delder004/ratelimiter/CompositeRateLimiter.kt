package io.github.delder004.ratelimiter

import kotlinx.coroutines.delay
import kotlin.time.Duration

/**
 * Creates a limiter that acquires permits from every supplied [limiters].
 *
 * A request succeeds only when all delegates can satisfy it. When every
 * delegate is one of the library's own implementations, every delegate is
 * reserved synchronously up front, the caller delays once for the longest
 * reservation, and a cancellation rolls every reservation back (fast-path
 * exact rewind for both fixed-interval and warming-smooth state). The
 * rollback iterates the delegates in reverse and is not globally atomic —
 * a concurrent observer touching delegates directly could see partially
 * rolled-back state mid-loop. For third-party [RefundableRateLimiter]
 * delegates the legacy sequential acquire-with-rollback is used as a
 * fallback.
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

    private val allReservable: Boolean = limiters.all { it is ReservableRateLimiter }

    override suspend fun acquire(permits: Int) {
        require(permits > 0) { "Permits must be positive, was $permits" }
        if (allReservable) {
            atomicReserveAcquire(permits)
        } else {
            acquireAllOrRollback(permits)
        }
    }

    override fun tryAcquire(permits: Int): Permit {
        require(permits > 0) { "Permits must be positive, was $permits" }
        return tryAcquireAllOrRollback(permits)
    }

    private suspend fun atomicReserveAcquire(permits: Int) {
        val reservations = mutableListOf<Reservation>()
        try {
            limiters.forEach { limiter ->
                reservations += (limiter as ReservableRateLimiter).reserve(permits)
            }
            delay(reservations.maxOf { it.wait })
        } catch (e: Throwable) {
            reservations.asReversed().forEach { it.cancel() }
            throw e
        }
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
            val wait =
                if (limiter is PeekableRateLimiter) {
                    // Non-mutating probe: avoids the `tryAcquire + refund`
                    // asymmetry on WarmingSmoothLimiter (grant doesn't add
                    // heat, refund always subtracts it).
                    limiter.peekWait(permits)
                } else {
                    // Third-party RefundableRateLimiter: fall back to probe +
                    // rollback, inheriting whatever semantics its refund provides.
                    when (val permit = limiter.tryAcquire(permits)) {
                        is Permit.Granted -> {
                            limiter.refund(permits)
                            Duration.ZERO
                        }
                        is Permit.Denied -> permit.retryAfter
                    }
                }
            maxRetryAfter = maxOf(maxRetryAfter, wait)
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
