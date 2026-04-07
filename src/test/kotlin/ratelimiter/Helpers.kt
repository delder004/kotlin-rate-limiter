package ratelimiter

import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime


// Record delays for a series of acquires
@OptIn(ExperimentalCoroutinesApi::class)
suspend fun TestScope.recordDelays(limiter: RateLimiter, count: Int): List<Long> {
    return (1..count).map {
        val before = currentTime
        limiter.acquire()
        currentTime - before
    }
}

// Assert that no more than `limit` events occurred in any `window`-sized slice
fun assertRateNotExceeded(timestamps: List<Long>, limit: Int, windowMs: Long) {
    for (start in timestamps) {
        val count = timestamps.count { it in start..(start + windowMs) }
        assertTrue(count <= limit, "Rate exceeded: $count events in ${windowMs}ms window starting at $start")
    }
}


sealed interface Op {
    data class Acquire(val permits: Int = 0) : Op
    data class TryAcquire(val permits: Int = 0) : Op
    data class Advance(val duration: Duration = Duration.ZERO) : Op
    data class Parallel(val acquires: List<Int>) : Op
}

data class OpResult(
    val op: Op,
    val startedAt: Long,
    val endedAt: Long,
    val permit: Permit? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
suspend fun TestScope.runScenario(limiter: RateLimiter, vararg ops: Op): List<OpResult> {
    return ops.map { op ->
        when (op) {
            is Op.Acquire -> {
                val started = currentTime
                limiter.acquire(op.permits)
                OpResult(op, started, currentTime)
            }
            is Op.TryAcquire -> {
                val started = currentTime
                val result = limiter.tryAcquire(op.permits)
                OpResult(op, started, currentTime, result)
            }
            is Op.Advance -> {
                val started = currentTime
                advanceTimeBy(op.duration)
                OpResult(op, started, currentTime)
            }
            is Op.Parallel -> {
                val started = currentTime
                launch { op.acquires.forEach { limiter.acquire(it) }}
                OpResult(op, started, currentTime)
            }
        }
    }
}