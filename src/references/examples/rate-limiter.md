# Rate Limiter

## Problem Statement
Design a rate limiter service that restricts the number of requests a client can make to an API
within a given time window.

## Requirements

**Functional**
- Enforce per-user (or per-API-key) request limits
- Support multiple limit rules (e.g., 100 req/min, 1000 req/day)
- Return HTTP 429 with a `Retry-After` header when limit is exceeded

**Non-Functional**
- Scale: hundreds of thousands of clients; millions of requests/sec across the fleet
- Latency: rate limit check must add <5ms p99 to request path
- Availability: prefer allowing requests over false-rejecting (AP over CP)
- Consistency: soft enforcement is acceptable; occasional over-limit is tolerable

## High-Level Design

```
Client → API Gateway → Rate Limiter Middleware → Upstream Service
                              ↓
                         Redis Cluster
```

- Rate limiter runs as middleware (sidecar or library) on the API gateway
- State is stored in Redis for fast, shared access across gateway replicas
- Each check is a single Redis command (atomic increment or Lua script)

## Algorithm Choice

| Algorithm | Pros | Cons |
|---|---|---|
| Fixed Window Counter | Simple, O(1) memory | Burst at window boundary |
| Sliding Window Log | Exact, no boundary burst | O(n) memory per user |
| Sliding Window Counter | Good approximation, O(1) | Slight inaccuracy |
| Token Bucket | Smooth bursting | Slightly complex |
| Leaky Bucket | Strict output rate | No burst tolerance |

**Recommended for L4**: Token Bucket or Sliding Window Counter — balance simplicity and accuracy.

## Data Model (Redis)

```
Key:   rate_limit:{user_id}:{window_start_minute}
Value: integer (request count)
TTL:   window size + small buffer (e.g., 70s for a 60s window)
```

## Pseudo-code (Token Bucket)

```
function isAllowed(userId, limit, windowSeconds):
    key = "rl:" + userId
    now = currentTimestamp()

    pipe = redis.pipeline()
    pipe.get(key)                          # get current tokens
    result = pipe.execute()

    tokens, lastRefill = deserialize(result[0]) or (limit, now)

    # Refill tokens based on elapsed time
    elapsed = now - lastRefill
    refillRate = limit / windowSeconds
    tokens = min(limit, tokens + elapsed * refillRate)

    if tokens >= 1:
        tokens -= 1
        redis.set(key, serialize(tokens, now), ex=windowSeconds)
        return ALLOW
    else:
        return DENY  # return 429 with Retry-After
```

## API Design

```
# Internal check endpoint (called by gateway middleware)
POST /internal/ratelimit/check
{
  "user_id": "u123",
  "resource": "api:v1:search",
  "cost": 1
}

Response 200: { "allowed": true, "remaining": 42, "reset_at": 1712345678 }
Response 429: { "allowed": false, "retry_after_seconds": 14 }
```

## Scaling & Trade-offs

**Redis cluster**: Shard by `user_id` hash — each user always hits the same shard, preserving
counter accuracy without cross-shard coordination.

**Race condition**: Use a Lua script (atomic read-modify-write) or Redis `INCR` + `EXPIRE` to
avoid TOCTOU bugs.

**Multi-region**: Accept soft over-limit across regions (eventual consistency). Use local Redis
per region and sync limits asynchronously if strict global enforcement is needed.

**Bypass on Redis failure**: Circuit-break to allow-all rather than block-all. Rate limiting is
a reliability feature, not a security gate — false rejections hurt more than occasional over-limit.

## Key Decisions & Why

| Decision | Reason |
|---|---|
| Redis over in-memory | Shared state across gateway replicas |
| Lua script for atomicity | Prevents double-counting under concurrency |
| Token Bucket over Fixed Window | Avoids burst at window boundaries |
| AP over CP | Occasional over-limit is better than false 429s |

## What L4 Candidates Often Miss

- Handling Redis failure (circuit breaker to allow-all)
- Race conditions in read-then-write without atomic operations
- Not returning `Retry-After` or `X-RateLimit-Remaining` headers
- Using a single Redis node (SPOF) instead of a cluster
- Not considering different limits for different resources/tiers