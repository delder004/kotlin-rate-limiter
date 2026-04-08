# Rate-Limited HTTP Client Wrapper

An alternative pattern for wrapping any HTTP client with per-route rate limiting. Use this when you do not want a framework-specific integration or when you need to rate limit something other than the actual Ktor request pipeline.

If you are already using Ktor, prefer [`KtorClientPluginExample.md`](KtorClientPluginExample.md). That keeps rate limiting at the HTTP boundary and is the repo's primary example for route-based client throttling.

## Using Prefix Matching

Use when your routes have a clean hierarchy and don't overlap (e.g., `/search`, `/uploads`, `/users`).

```kotlin
import io.ktor.client.*
import io.ktor.client.statement.*
import ratelimiter.*
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

data class RouteRule(
    val prefix: String,
    val limiter: RefundableRateLimiter,
)

class RateLimitedClient(
    private val client: HttpClient = HttpClient(),
    private val defaultLimiter: RefundableRateLimiter? = BurstyRateLimiter(permits = 100, per = 1.minutes),
    private val routeLimiters: List<RouteRule> = emptyList(),
) {
    suspend fun <T> request(route: String, block: suspend HttpClient.() -> T): T {
        val routeLimiter = routeLimiters
            .firstOrNull { route == it.prefix || route.startsWith("${it.prefix}/") }
            ?.limiter

        val limiter = when {
            defaultLimiter != null && routeLimiter != null -> CompositeRateLimiter(defaultLimiter, routeLimiter)
            routeLimiter != null -> routeLimiter
            else -> defaultLimiter
        }

        return if (limiter == null) {
            client.block()
        } else {
            limiter.withPermit { client.block() }
        }
    }
}
```

### Usage

```kotlin
val client = RateLimitedClient(
    defaultLimiter = BurstyRateLimiter(permits = 100, per = 1.minutes),
    routeLimiters = listOf(
        RouteRule("/search", SmoothRateLimiter(permits = 2, per = 1.seconds)),
        RouteRule("/uploads", BurstyRateLimiter(permits = 5, per = 1.minutes)),
    ),
)

val results = client.request("/search") {
    get("https://api.example.com/search?q=foo")
}

val upload = client.request("/uploads") {
    post("https://api.example.com/uploads") { setBody(data) }
}
```

In this shape, `/search` requests must satisfy both the global `100/minute` limiter and the route-specific `2/second` limiter. Unmatched routes fall back to the global limiter only.

Prefix matching is simpler, faster, and harder to get wrong. Choose this unless you have a specific reason to need regex.

## Using Regex Matching

Use when routes share a prefix but need different limits. For example, `/users` (list all) vs `/users/\d+` (single user) would both match the same prefix, but regex lets you distinguish them.

```kotlin
data class RegexRouteRule(
    val pattern: Regex,
    val limiter: RefundableRateLimiter,
)

class RateLimitedClient(
    private val client: HttpClient = HttpClient(),
    private val defaultLimiter: RefundableRateLimiter? = BurstyRateLimiter(permits = 100, per = 1.minutes),
    private val routeLimiters: List<RegexRouteRule> = emptyList(),
) {
    suspend fun <T> request(route: String, block: suspend HttpClient.() -> T): T {
        val routeLimiter = routeLimiters
            .firstOrNull { it.pattern.matches(route) }
            ?.limiter

        val limiter = when {
            defaultLimiter != null && routeLimiter != null -> CompositeRateLimiter(defaultLimiter, routeLimiter)
            routeLimiter != null -> routeLimiter
            else -> defaultLimiter
        }

        return if (limiter == null) {
            client.block()
        } else {
            limiter.withPermit { client.block() }
        }
    }
}
```

### Usage

```kotlin
val client = RateLimitedClient(
    defaultLimiter = BurstyRateLimiter(permits = 100, per = 1.minutes),
    routeLimiters = listOf(
        RegexRouteRule(Regex("/users"), BurstyRateLimiter(permits = 20, per = 1.seconds)),
        RegexRouteRule(Regex("/users/\\d+"), SmoothRateLimiter(permits = 5, per = 1.seconds)),
        RegexRouteRule(Regex("/search.*"), BurstyRateLimiter(permits = 2, per = 1.seconds)),
    ),
)

val users = client.request("/users") {
    get("https://api.example.com/users")
}

val user = client.request("/users/42") {
    get("https://api.example.com/users/42")
}
```

As with prefix matching, regex routes are checked in declaration order. A matched regex route can be composed with the global limiter so both limits apply to the same request.

### When to use which

| | Prefix | Regex |
|---|---|---|
| Routes are hierarchical (`/search`, `/uploads`) | Good fit | Overkill |
| Routes share a prefix (`/users` vs `/users/\d+`) | Ambiguous | Good fit |
| Dynamic path segments (`/orders/\d+/items`) | Can't express | Good fit |
| Readability | Obvious | Requires care |

## Layered Limits with CompositeRateLimiter

Both approaches work with `CompositeRateLimiter` when an API enforces multiple limits simultaneously. The important part is that a matched route must compose the global and route-specific limiters for that request, rather than replacing one with the other:

```kotlin
val client = RateLimitedClient(
    defaultLimiter = BurstyRateLimiter(permits = 10, per = 1.seconds),
    routeLimiters = listOf(
        RouteRule(
            "/search",
            CompositeRateLimiter(
                BurstyRateLimiter(permits = 1000, per = 1.hours),
                SmoothRateLimiter(permits = 2, per = 1.seconds),
            ),
        ),
    ),
)
```

That gives `/search` requests all three constraints: the global `10/second` limiter, the route-specific `1000/hour` limiter, and the route-specific `2/second` smoother.

## Notes

- Each `RateLimiter` instance tracks its own independent token bucket. They are all thread-safe and work concurrently without interfering with each other.
- Route rules are evaluated in declaration order. The first match wins.
- For a more integrated approach using a Ktor client plugin, see [`KtorClientPluginExample.md`](KtorClientPluginExample.md).
