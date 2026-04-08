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

class RateLimitedClient(
    private val client: HttpClient = HttpClient(),
    private val defaultLimiter: RateLimiter = BurstyRateLimiter(permits = 10, per = 1.seconds),
    private val routeLimiters: Map<String, RateLimiter> = emptyMap(),
) {
    suspend fun <T> request(route: String, block: suspend HttpClient.() -> T): T {
        val limiter = routeLimiters.entries
            .firstOrNull { route.startsWith(it.key) }
            ?.value
            ?: defaultLimiter

        return limiter.withPermit { client.block() }
    }
}
```

### Usage

```kotlin
val client = RateLimitedClient(
    routeLimiters = mapOf(
        "/search" to SmoothRateLimiter(permits = 2, per = 1.seconds),
        "/uploads" to BurstyRateLimiter(permits = 5, per = 1.minutes),
    ),
)

val results = client.request("/search") {
    get("https://api.example.com/search?q=foo")
}

val upload = client.request("/uploads") {
    post("https://api.example.com/uploads") { setBody(data) }
}
```

Prefix matching is simpler, faster, and harder to get wrong. Choose this unless you have a specific reason to need regex.

## Using Regex Matching

Use when routes share a prefix but need different limits. For example, `/users` (list all) vs `/users/\d+` (single user) would both match the same prefix, but regex lets you distinguish them.

```kotlin
class RateLimitedClient(
    private val client: HttpClient = HttpClient(),
    private val defaultLimiter: RateLimiter = BurstyRateLimiter(permits = 10, per = 1.seconds),
    private val routeLimiters: List<Pair<Regex, RateLimiter>> = emptyList(),
) {
    suspend fun <T> request(route: String, block: suspend HttpClient.() -> T): T {
        val limiter = routeLimiters
            .firstOrNull { (pattern, _) -> pattern.matches(route) }
            ?.second
            ?: defaultLimiter

        return limiter.withPermit { client.block() }
    }
}
```

### Usage

```kotlin
val client = RateLimitedClient(
    routeLimiters = listOf(
        Regex("/users") to BurstyRateLimiter(permits = 20, per = 1.seconds),
        Regex("/users/\\d+") to SmoothRateLimiter(permits = 5, per = 1.seconds),
        Regex("/search.*") to BurstyRateLimiter(permits = 2, per = 1.seconds),
    ),
)

val users = client.request("/users") {
    get("https://api.example.com/users")
}

val user = client.request("/users/42") {
    get("https://api.example.com/users/42")
}
```

### When to use which

| | Prefix | Regex |
|---|---|---|
| Routes are hierarchical (`/search`, `/uploads`) | Good fit | Overkill |
| Routes share a prefix (`/users` vs `/users/\d+`) | Ambiguous | Good fit |
| Dynamic path segments (`/orders/\d+/items`) | Can't express | Good fit |
| Readability | Obvious | Requires care |

## Layered Limits with CompositeRateLimiter

Both approaches work with `CompositeRateLimiter` when an API enforces multiple limits simultaneously:

```kotlin
val client = RateLimitedClient(
    defaultLimiter = CompositeRateLimiter(
        BurstyRateLimiter(permits = 10, per = 1.seconds),
        BurstyRateLimiter(permits = 1000, per = 1.hours),
    ),
    routeLimiters = mapOf(
        "/search" to SmoothRateLimiter(permits = 2, per = 1.seconds),
    ),
)
```

## Notes

- Each `RateLimiter` instance tracks its own independent token bucket. They are all thread-safe and work concurrently without interfering with each other.
- Route rules are evaluated in declaration order. The first match wins.
- For a more integrated approach using a Ktor client plugin, see [`KtorClientPluginExample.md`](KtorClientPluginExample.md).
