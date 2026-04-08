# Ktor Client Plugin Example

`kotlin-rate-limiter` does not ship with a built-in Ktor integration, but it maps cleanly onto a client plugin. The example below keeps rate limiting at the HTTP boundary so every outbound request goes through the same policy.

## What the plugin does

- Matches requests by HTTP method and route
- Uses path prefix matching for the common case
- Supports regex matching when routes are dynamic
- Applies a configured `RateLimiter` before the request is executed
- Supports two acquire modes:
  - `Suspend`: wait until permits are available
  - `FailFast`: call `tryAcquire()` and throw immediately when denied

The tested example implementation lives in:

- `src/examples/kotlin/ratelimiter/examples/KtorClientRateLimitingPlugin.kt`
- `src/test/kotlin/ratelimiter/examples/KtorClientRateLimitingPluginTest.kt`

## Example

```kotlin
val allRoutes = BurstyRateLimiter(permits = 100, per = 1.minutes)
val userReads = BurstyRateLimiter(permits = 20, per = 1.minutes)
val payments = BurstyRateLimiter(permits = 5, per = 1.minutes)

val client = HttpClient {
    install(RateLimitingPlugin) {
        defaultLimiter = allRoutes

        route(
            "/users",
            limiter = CompositeRateLimiter(allRoutes, userReads),
            method = HttpMethod.Get,
        )

        route(
            "/payments",
            limiter = CompositeRateLimiter(allRoutes, payments),
            method = HttpMethod.Post,
        )

        route(
            Regex("/search/\\w+"),
            limiter = CompositeRateLimiter(allRoutes, userReads),
            method = HttpMethod.Get,
        )
    }
}
```

## Why this shape

This is simpler than wrapping `HttpClient` in a separate `RateLimitedClient` because:

- It rate limits the actual HTTP boundary instead of relying on callers to remember `client.request(route) { ... }`
- Prefix routes are easy to read and cover most APIs
- Regex stays available as an escape hatch for dynamic paths
- You can still use a custom `predicate` when path matching is not enough

Recommended API shape:

```kotlin
install(RateLimitingPlugin) {
    defaultLimiter = BurstyRateLimiter(permits = 100, per = 1.minutes)

    route("/search", limiter = SmoothRateLimiter(permits = 2, per = 1.seconds))
    route("/uploads", limiter = BurstyRateLimiter(permits = 5, per = 1.minutes))
    route(Regex("/users/\\d+"), limiter = BurstyRateLimiter(permits = 10, per = 1.minutes))
}
```

## Notes

- Route rules are evaluated in declaration order. The first matching rule wins.
- If you use `CompositeRateLimiter(global, routeSpecific)` for a matched route, do not also apply the same global limiter separately for that request path or you will double-charge the global quota.
- `FailFast` mode is useful when you want to return a cached value, throw a client exception, or translate rate limiting into a retryable application error instead of suspending.
