# Documentation

API reference for `kotlin-rate-limiter`.

| Doc | Description |
|-----|-------------|
| [RateLimiter](RateLimiter.md) | Core interface — `acquire()` and `tryAcquire()` |
| [BurstyRateLimiter](BurstyRateLimiter.md) | Token bucket — allows bursts, then paces |
| [SmoothRateLimiter](SmoothRateLimiter.md) | Even distribution — optional warmup ramp |
| [CompositeRateLimiter](CompositeRateLimiter.md) | Combine multiple limiters — all must grant |
| [Extensions](Extensions.md) | `withPermit {}` and `Flow.rateLimit()` |
