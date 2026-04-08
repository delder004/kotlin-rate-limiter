package ratelimiter.examples

import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import ratelimiter.Permit
import ratelimiter.RateLimiter
import kotlin.time.Duration

enum class AcquireMode {
    Suspend,
    FailFast,
}

class RateLimitExceededException(
    val retryAfter: Duration,
    message: String,
) : RuntimeException(message)

class RateLimitingConfig {
    var defaultLimiter: RateLimiter? = null
    var acquireMode: AcquireMode = AcquireMode.Suspend
    var onDenied: (HttpRequestBuilder, Permit.Denied) -> Throwable =
        { request, denial ->
            val method = request.method.value
            val path = request.url.pathString()
            RateLimitExceededException(
                retryAfter = denial.retryAfter,
                message = "Rate limited for $method $path, retry after ${denial.retryAfter}",
            )
        }

    private val routes = mutableListOf<RouteRule>()

    // Rules are matched in declaration order. The first matching rule wins.
    internal fun findLimiter(request: HttpRequestBuilder): RateLimiter? =
        routes.firstOrNull { it.matches(request) }?.limiter ?: defaultLimiter

    fun route(
        pathPrefix: String,
        limiter: RateLimiter,
        method: HttpMethod? = null,
        predicate: (HttpRequestBuilder) -> Boolean = { true },
    ) {
        routes +=
            RouteRule(
                limiter = limiter,
                method = method,
                pathMatcher = RoutePathMatcher.Prefix(pathPrefix),
                predicate = predicate,
            )
    }

    fun route(
        pathRegex: Regex,
        limiter: RateLimiter,
        method: HttpMethod? = null,
        predicate: (HttpRequestBuilder) -> Boolean = { true },
    ) {
        routes +=
            RouteRule(
                limiter = limiter,
                method = method,
                pathMatcher = RoutePathMatcher.Regex(pathRegex),
                predicate = predicate,
            )
    }
}

private class RouteRule(
    val limiter: RateLimiter,
    val method: HttpMethod?,
    val pathMatcher: RoutePathMatcher,
    val predicate: (HttpRequestBuilder) -> Boolean,
) {
    fun matches(request: HttpRequestBuilder): Boolean {
        val methodMatches = method == null || request.method == method
        val pathMatches = pathMatcher.matches(request.url.pathString())
        return methodMatches && pathMatches && predicate(request)
    }
}

private sealed interface RoutePathMatcher {
    fun matches(path: String): Boolean

    data object Any : RoutePathMatcher {
        override fun matches(path: String): Boolean = true
    }

    data class Prefix(
        val value: String,
    ) : RoutePathMatcher {
        override fun matches(path: String): Boolean = path == value || path.startsWith("$value/")
    }

    data class Regex(
        val value: kotlin.text.Regex,
    ) : RoutePathMatcher {
        override fun matches(path: String): Boolean = value.matches(path)
    }
}

private fun URLBuilder.pathString(): String =
    encodedPathSegments
        .filter { it.isNotEmpty() }
        .joinToString(separator = "/", prefix = "/")

val RateLimitingPlugin =
    createClientPlugin(
        name = "RateLimitingPlugin",
        createConfiguration = ::RateLimitingConfig,
    ) {
        val config = pluginConfig

        onRequest { request, _ ->
            val limiter = config.findLimiter(request) ?: return@onRequest

            when (config.acquireMode) {
                AcquireMode.Suspend -> limiter.acquire()
                AcquireMode.FailFast ->
                    when (val permit = limiter.tryAcquire()) {
                        Permit.Granted -> Unit
                        is Permit.Denied -> throw config.onDenied(request, permit)
                    }
            }
        }
    }
