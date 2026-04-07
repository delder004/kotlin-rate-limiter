package ratelimiter

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class InvariantTest {
    companion object {
        private val seeds = listOf(42L, 123L, 999L, 7L, 2024L)
        private val limiterTypes = listOf("bursty", "smooth", "composite")

        @JvmStatic
        fun cases(): List<Arguments> =
            seeds.flatMap { seed ->
                limiterTypes.map { type -> Arguments.of(seed, type) }
            }
    }

    @ParameterizedTest(name = "seed={0} limiter={1}")
    @MethodSource("cases")
    fun `invariants hold for random scenarios`(
        seed: Long,
        limiterType: String,
    ) = runTest {
        val limiter =
            when (limiterType) {
                "bursty" -> BurstyRateLimiter(10, 1.seconds, testTimeSource)
                "smooth" -> SmoothRateLimiter(10, 1.seconds, Duration.ZERO, testTimeSource)
                "composite" ->
                    CompositeRateLimiter(
                        BurstyRateLimiter(10, 1.seconds, testTimeSource),
                        SmoothRateLimiter(10, 1.seconds, Duration.ZERO, testTimeSource),
                    )
                else -> error("unknown limiter type: $limiterType")
            }
        val ops = Random(seed).scenario(200)
        val results = runScenario(limiter, *ops)
        assertInvariants(results)
    }
}
