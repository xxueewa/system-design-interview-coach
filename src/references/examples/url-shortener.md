# URL Shortener (e.g., bit.ly)

## Problem Statement
Design a service that takes a long URL and returns a short alias. Visiting the short URL
redirects the user to the original URL.

## Requirements

**Functional**
- Shorten a URL → return a unique short code (e.g., `sho.rt/aB3xYz`)
- Redirect short URL to the original URL
- Optional: custom aliases, expiration dates, click analytics

**Non-Functional**
- Scale: 100M URLs created/day; 10B redirects/day (~115K redirects/sec)
- Read/write ratio: heavily read-skewed (~100:1)
- Latency: redirect must be <10ms p99
- Availability: 99.99% uptime; eventual consistency acceptable
- Short codes: 6–8 characters, URL-safe, collision-resistant

## High-Level Design

```
Write path:
  Client → API Gateway → Shortener Service → DB (Postgres)
                                           → Cache (Redis) [optional warm-up]

Read path:
  Client → CDN / Edge Cache → Redirect Service → Redis → Postgres (fallback)
```

- Writes are rare; reads dominate → aggressively cache redirects at edge and in Redis
- A CDN can serve the 301/302 redirect for popular URLs with no origin hit

## Short Code Generation

**Option A — Base62 encoding of auto-incremented ID**
```
alphabet = "0-9A-Za-z"  # 62 chars
encode(id):
    while id > 0:
        result = alphabet[id % 62] + result
        id = id // 62
    return result.padStart(6, '0')
```
- Predictable, no collisions, sequential IDs are guessable (privacy concern)

**Option B — Random 6-char Base62 + collision check**
- 62^6 ≈ 56B combinations; collision probability low at 100M URLs
- Check DB before insert; retry on collision

**Option C — MD5/SHA256 of URL → take first 6 chars Base62**
- Deterministic (same URL → same code); easy deduplication
- Rare but possible collisions still need handling

**Recommended for L4**: Option A with a distributed ID generator (Snowflake) to avoid sequential
guessing while keeping collision-free generation.

## Data Model

```sql
-- URLs table (Postgres)
CREATE TABLE urls (
  id          BIGINT PRIMARY KEY,          -- Snowflake ID
  short_code  VARCHAR(8) UNIQUE NOT NULL,
  long_url    TEXT NOT NULL,
  user_id     BIGINT,
  created_at  TIMESTAMPTZ DEFAULT now(),
  expires_at  TIMESTAMPTZ,
  click_count BIGINT DEFAULT 0            -- or track separately
);

CREATE INDEX idx_short_code ON urls(short_code);
```

```
-- Redis cache
Key:   url:{short_code}
Value: long_url (string)
TTL:   24h (or until expiration)
```

## API Design

```
# Create short URL
POST /api/v1/urls
Body: { "long_url": "https://...", "alias": "mylink", "expires_in_days": 30 }
Response 201: { "short_url": "https://sho.rt/aB3xYz", "short_code": "aB3xYz" }

# Redirect (public-facing)
GET /{short_code}
Response 302: Location: <long_url>   # 302 for analytics tracking
Response 301: Location: <long_url>   # 301 if caching at browser/CDN is desired

# Get URL info (authenticated)
GET /api/v1/urls/{short_code}
Response 200: { "long_url": "...", "clicks": 1042, "created_at": "..." }
```

## Scaling & Trade-offs

**Read path**: Serve redirects from Redis (sub-ms) → fall back to Postgres only on cache miss.
Popular short codes can be cached at the CDN edge for near-zero latency.

**Write path**: Sharding Postgres by `short_code` hash if needed at very large scale. For most
scales a single primary with read replicas is sufficient given the low write rate.

**Analytics**: Don't block the redirect to record a click. Fire-and-forget to a Kafka topic;
a consumer asynchronously increments the click counter or writes to a time-series store.

**Expiration**: A background job (cron or scheduled Lambda) deletes expired rows. Redis TTL
handles cache expiry automatically.

**301 vs 302**: 301 (permanent) lets browsers cache the redirect — saves future origin hits but
prevents click tracking. 302 (temporary) hits the server each time — enables analytics but adds
latency. Use 302 with CDN caching for a balance.

## Key Decisions & Why

| Decision | Reason |
|---|---|
| Snowflake ID + Base62 | Collision-free, no sequential guessing, fast encode |
| Redis for redirect lookup | Redirect latency must be <10ms; DB alone is too slow |
| 302 redirect | Preserves analytics tracking ability |
| Kafka for click events | Decouples analytics from the hot redirect path |
| CDN caching | Offloads redirect traffic for popular links |

## What L4 Candidates Often Miss

- Not discussing 301 vs 302 trade-off
- Forgetting cache invalidation when a URL is deleted or expired
- Using a single DB node (SPOF) on the read path
- Not handling hash collisions in code generation
- Blocking the redirect response to record analytics (increases latency)
- Not considering URL validation/sanitization (SSRF risk if internal URLs are allowed)