package ratelimiter

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

// Record delays for a series of acquires
@OptIn(ExperimentalCoroutinesApi::class)
suspend fun TestScope.recordDelays(
    limiter: RateLimiter,
    count: Int,
): List<Long> =
    (1..count).map {
        val before = currentTime
        limiter.acquire()
        currentTime - before
    }

// Assert that no more than `limit` events occurred in any `window`-sized slice
fun assertRateNotExceeded(
    timestamps: List<Long>,
    limit: Int,
    windowMs: Long,
) {
    for (start in timestamps) {
        val count = timestamps.count { it in start..<(start + windowMs) }
        assertTrue(count <= limit, "Rate exceeded: $count events in ${windowMs}ms window starting at $start")
    }
}

sealed interface Op {
    data class Acquire(
        val permits: Int = 1,
    ) : Op

    data class TryAcquire(
        val permits: Int = 1,
    ) : Op

    data class Advance(
        val duration: Duration = Duration.ZERO,
    ) : Op

    data class Parallel(
        val acquires: List<Int>,
    ) : Op
}

data class OpResult(
    val op: Op,
    val startedAt: Long,
    val endedAt: Long,
    val permit: Permit? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
suspend fun TestScope.runScenario(
    limiter: RateLimiter,
    vararg ops: Op,
): List<OpResult> =
    ops
        .map { op ->
            when (op) {
                is Op.Acquire -> {
                    val started = currentTime
                    limiter.acquire(op.permits)
                    listOf(OpResult(op, started, currentTime))
                }
                is Op.TryAcquire -> {
                    val started = currentTime
                    val result = limiter.tryAcquire(op.permits)
                    listOf(OpResult(op, started, currentTime, result))
                }
                is Op.Advance -> {
                    val started = currentTime
                    advanceTimeBy(op.duration)
                    listOf(OpResult(op, started, currentTime))
                }
                is Op.Parallel -> {
                    val deferred =
                        op.acquires.map {
                            async {
                                val started = currentTime
                                limiter.acquire(it)
                                OpResult(Op.Acquire(it), started, currentTime)
                            }
                        }
                    advanceUntilIdle()
                    deferred.awaitAll()
                }
            }
        }.flatten()

data class OpWeights(
    val acquire: Int = 25,
    val tryAcquire: Int = 10,
    val advance: Int = 40,
    val parallel: Int = 25,
)

fun Random.nextOp(w: OpWeights = OpWeights()): Op {
    val total = w.acquire + w.tryAcquire + w.advance + w.parallel
    val roll = nextInt(total)
    return when {
        roll < w.acquire -> Op.Acquire(nextInt(1, 5))
        roll < w.acquire + w.tryAcquire -> Op.TryAcquire(nextInt(1, 5))
        roll < w.acquire + w.tryAcquire + w.advance -> Op.Advance(nextInt(50, 2000).milliseconds)
        else -> Op.Parallel((1..nextInt(2, 10)).map { nextInt(1, 3) })
    }
}

fun Random.scenario(
    length: Int,
    weights: OpWeights = OpWeights(),
): Array<Op> = Array(length) { nextOp(weights) }

fun assertInvariants(results: List<OpResult>) {
    results.forEach { r ->
        assertTrue(r.endedAt >= r.startedAt, "endedAt should be >= startedAt: $r")
    }
    results.filter { it.op is Op.TryAcquire }.forEach { r ->
        assertEquals(r.startedAt, r.endedAt, "tryAcquire should never suspend: $r")
    }
    results.filter { it.permit is Permit.Denied }.forEach { r ->
        assertTrue(
            (r.permit as Permit.Denied).retryAfter > Duration.ZERO,
            "Denied retryAfter should be positive: $r",
        )
    }
}
