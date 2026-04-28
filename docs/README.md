# Documentation

API reference for `kotlin-rate-limiter`.

| Doc | Description |
|-----|-------------|
| [RateLimiter](RateLimiter.md) | Core interface — `acquire()` and `tryAcquire()` |
| [RefundableRateLimiter](RefundableRateLimiter.md) | `RateLimiter` plus `refund()` — returned by every factory |
| [BurstyRateLimiter](BurstyRateLimiter.md) | Token bucket — allows bursts, then paces |
| [SmoothRateLimiter](SmoothRateLimiter.md) | Even distribution — optional warmup ramp |
| [CompositeRateLimiter](CompositeRateLimiter.md) | Combine multiple limiters — all must grant |
| [Extensions](Extensions.md) | `withPermit {}` and `Flow.rateLimit()` |
| [KtorClientPluginExample](KtorClientPluginExample.md) | Example Ktor client plugin for route-based rate limiting |
| [RateLimitedHttpClient](RateLimitedHttpClient.md) | Framework-agnostic HTTP client wrapper example |
| [Observability](Observability.md) | Adding metrics via a decorator wrapper |
