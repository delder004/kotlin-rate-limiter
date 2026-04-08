# Documentation

API reference for `kotlin-rate-limiter`.

| Doc | Description |
|-----|-------------|
| [RateLimiter](RateLimiter.md) | Core interface — `acquire()` and `tryAcquire()` |
| [BurstyRateLimiter](BurstyRateLimiter.md) | Token bucket — allows bursts, then paces |
| [SmoothRateLimiter](SmoothRateLimiter.md) | Even distribution — optional warmup ramp |
| [Extensions](Extensions.md) | `withPermit {}` and `Flow.rateLimit()` |
