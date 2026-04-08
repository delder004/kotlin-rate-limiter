package ratelimiter.examples

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import ratelimiter.BurstyRateLimiter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class KtorClientRateLimitingPluginTest {
    @Test
    fun `default limiter rate limits all requests`() =
        runTest {
            val timestamps = mutableListOf<Long>()
            val limiter = BurstyRateLimiter(permits = 1, per = 1.seconds, timeSource = testTimeSource)

            val client =
                testClient(
                    configure = {
                        defaultLimiter = limiter
                    },
                    handler = {
                        timestamps += currentTime
                        respond(
                            content = "ok",
                            status = HttpStatusCode.OK,
                        )
                    },
                )

            client.get("https://example.com/a")
            client.get("https://example.com/b")

            assertEquals(listOf(0L, 1000L), timestamps)
        }

    @Test
    fun `route rules can target method and path without affecting other routes`() =
        runTest {
            val userTimestamps = mutableListOf<Long>()
            val healthTimestamps = mutableListOf<Long>()
            val limiter = BurstyRateLimiter(permits = 1, per = 1.seconds, timeSource = testTimeSource)

            val client =
                testClient(
                    configure = {
                        route("/users", limiter = limiter, method = HttpMethod.Get)
                    },
                    handler = { request ->
                        when {
                            request.method == HttpMethod.Get && request.url.pathString().startsWith("/users") ->
                                userTimestamps += currentTime
                            request.url.pathString().startsWith("/health") -> healthTimestamps += currentTime
                        }

                        respond(
                            content = "ok",
                            status = HttpStatusCode.OK,
                        )
                    },
                )

            client.get("https://example.com/users/1")
            client.get("https://example.com/health")
            client.get("https://example.com/users/2")
            client.post("https://example.com/users")

            assertEquals(listOf(0L, 1000L), userTimestamps)
            assertEquals(listOf(0L), healthTimestamps)
        }

    @Test
    fun `prefix route does not match sibling path with same prefix`() =
        runTest {
            val userTimestamps = mutableListOf<Long>()
            val siblingTimestamps = mutableListOf<Long>()
            val limiter = BurstyRateLimiter(permits = 1, per = 1.seconds, timeSource = testTimeSource)

            val client =
                testClient(
                    configure = {
                        route("/users", limiter = limiter, method = HttpMethod.Get)
                    },
                    handler = { request ->
                        when {
                            request.url.pathString().startsWith("/users/") || request.url.pathString() == "/users" ->
                                userTimestamps += currentTime
                            request.url.pathString().startsWith("/userships") -> siblingTimestamps += currentTime
                        }

                        respond(
                            content = "ok",
                            status = HttpStatusCode.OK,
                        )
                    },
                )

            client.get("https://example.com/users")
            client.get("https://example.com/userships")
            client.get("https://example.com/users/1")

            assertEquals(listOf(0L, 1000L), userTimestamps)
            assertEquals(listOf(0L), siblingTimestamps)
        }

    @Test
    fun `requests pass through when no limiter is configured`() =
        runTest {
            val timestamps = mutableListOf<Long>()

            val client =
                testClient(
                    configure = {},
                    handler = {
                        timestamps += currentTime
                        respond(
                            content = "ok",
                            status = HttpStatusCode.OK,
                        )
                    },
                )

            client.get("https://example.com/")
            client.get("https://example.com/users")

            assertEquals(listOf(0L, 0L), timestamps)
        }

    @Test
    fun `fail fast mode throws with retry hint instead of suspending`() =
        runTest {
            val limiter = BurstyRateLimiter(permits = 1, per = 1.seconds, timeSource = testTimeSource)

            val client =
                testClient(
                    configure = {
                        defaultLimiter = limiter
                        acquireMode = AcquireMode.FailFast
                    },
                    handler = {
                        respond(
                            content = "ok",
                            status = HttpStatusCode.OK,
                        )
                    },
                )

            client.get("https://example.com/a")

            val error =
                assertFailsWith<RateLimitExceededException> {
                    client.get("https://example.com/b")
                }

            assertEquals(0L, currentTime)
            assertTrue(error.retryAfter.inWholeMilliseconds > 0)
        }

    @Test
    fun `regex route can match dynamic paths`() =
        runTest {
            val matchedTimestamps = mutableListOf<Long>()
            val otherTimestamps = mutableListOf<Long>()
            val limiter = BurstyRateLimiter(permits = 1, per = 1.seconds, timeSource = testTimeSource)

            val client =
                testClient(
                    configure = {
                        route(Regex("/users/\\d+"), limiter = limiter, method = HttpMethod.Get)
                    },
                    handler = { request ->
                        when {
                            request.url.pathString().matches(Regex("/users/\\d+")) -> matchedTimestamps += currentTime
                            else -> otherTimestamps += currentTime
                        }

                        respond(
                            content = "ok",
                            status = HttpStatusCode.OK,
                        )
                    },
                )

            client.get("https://example.com/users/1")
            client.get("https://example.com/users")
            client.get("https://example.com/users/2")

            assertEquals(listOf(0L, 1000L), matchedTimestamps)
            assertEquals(listOf(0L), otherTimestamps)
        }

    @Test
    fun `custom denial exception can inspect the request`() =
        runTest {
            val limiter = BurstyRateLimiter(permits = 1, per = 1.seconds, timeSource = testTimeSource)

            val client =
                testClient(
                    configure = {
                        defaultLimiter = limiter
                        acquireMode = AcquireMode.FailFast
                        onDenied = { request, denial ->
                            IllegalStateException("${request.method.value} ${request.url.pathString()} ${denial.retryAfter}")
                        }
                    },
                    handler = {
                        respond(
                            content = "ok",
                            status = HttpStatusCode.OK,
                        )
                    },
                )

            client.get("https://example.com/a")

            val error =
                assertFailsWith<IllegalStateException> {
                    client.get("https://example.com/b")
                }

            assertTrue(error.message!!.startsWith("GET /b "))
        }

    @Test
    fun `route order controls which limiter applies when multiple rules match`() =
        runTest {
            val timestamps = mutableListOf<Long>()
            val firstLimiter = BurstyRateLimiter(permits = 1, per = 1.seconds, timeSource = testTimeSource)
            val secondLimiter = BurstyRateLimiter(permits = 1, per = 2.seconds, timeSource = testTimeSource)

            val client =
                testClient(
                    configure = {
                        route("/users", limiter = firstLimiter, method = HttpMethod.Get)
                        route("/users", limiter = secondLimiter, method = HttpMethod.Get)
                    },
                    handler = {
                        timestamps += currentTime
                        respond(
                            content = "ok",
                            status = HttpStatusCode.OK,
                        )
                    },
                )

            client.get("https://example.com/users/1")
            client.get("https://example.com/users/2")

            assertEquals(listOf(0L, 1000L), timestamps)
        }

    private fun testClient(
        configure: RateLimitingConfig.() -> Unit,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): HttpClient {
        val engine = MockEngine { request -> handler(request) }

        return HttpClient(engine) {
            install(RateLimitingPlugin) {
                configure()
            }
        }
    }

    private fun Url.pathString(): String =
        pathSegments
            .filter { it.isNotEmpty() }
            .joinToString(separator = "/", prefix = "/")

    private fun URLBuilder.pathString(): String =
        encodedPathSegments
            .filter { it.isNotEmpty() }
            .joinToString(separator = "/", prefix = "/")
}
