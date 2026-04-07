# News Feed System

## Problem Statement
Design a news feed system (like Facebook or Twitter home feed) that shows users a ranked,
personalized list of posts from people and pages they follow, updated in near real-time.

## Requirements

**Functional**
- Users can create posts (text, images, links)
- Users see a feed of posts from accounts they follow, ranked by relevance/time
- Feed updates when followed users post new content
- Support pagination (infinite scroll)

**Non-Functional**
- Scale: 500M DAU; 1B follow relationships; average 200 followees per user
- Read-heavy: feed reads >> post writes (100:1 ratio)
- Latency: feed load < 500ms p99
- Eventual consistency: slight delay in feed update is acceptable

## High-Level Design

```
Write Path:
  User → Post Service → DB + Message Queue → Feed Fanout Workers

Read Path:
  User → Feed Service → Feed Cache (Redis) → Ranked Feed Response
```

## Feed Generation Strategies

### Option A: Push (Fanout on Write) — for users with few followers
- When User A posts, immediately write to the feed cache of all followers
- Feed read is just a cache lookup — O(1), very fast
- Problem: celebrities with 10M followers cause write amplification

### Option B: Pull (Fanout on Read) — for celebrities
- When a user loads their feed, merge posts from all followees in real-time
- No write amplification, but read is expensive if followees are numerous

### Hybrid (Recommended)
- Regular users (<5K followers): fanout on write → pre-populate feed caches
- Celebrities (>5K followers): fanout on read — merge their posts at read time
- Result: fast reads for most users; bounded write amplification

## Write Path

```
Post Service:
  1. Write post to Posts DB (MySQL/Cassandra)
  2. Publish event to Kafka (topic: new-posts)

Fanout Worker (Kafka consumer):
  For each follower of poster:
    - If follower is active (logged in recently): write post_id to feed cache
    - Else: skip (lazy load when they next log in)
  Feed cache entry: sorted set in Redis, keyed by user_id, scored by timestamp
```

## Read Path

```
Feed Service:
  1. Read sorted set from Redis: ZREVRANGE feed:{user_id} 0 19 (top 20 post_ids)
  2. For celebrity followees: merge their recent posts (last 24h) in real-time
  3. Fetch post details for all post_ids from Posts Cache / DB
  4. Apply ranking model (recency + engagement signals)
  5. Return ranked list
```

## Data Model

```
posts:
  post_id      BIGINT PK (Snowflake)
  user_id      BIGINT
  content      TEXT
  media_urls   JSON
  created_at   TIMESTAMP
  like_count   INT
  comment_count INT

follows:
  follower_id  BIGINT
  followee_id  BIGINT
  PRIMARY KEY (follower_id, followee_id)

Feed Cache (Redis):
  Key: feed:{user_id}
  Type: Sorted Set
  Score: post creation timestamp
  Member: post_id
  Max size: 500 most recent post_ids per user
```

## Ranking

- Simple: sort by `created_at` descending
- Advanced: weighted score = `recency + engagement_rate + relationship_strength`
- ML model runs offline; scores stored as features; re-ranked at query time

## Pagination

- **Cursor-based**: return `next_cursor = last_seen_post_id`; next request fetches posts older than cursor
- Avoid offset-based pagination (inconsistent under new post insertions)

## API Design

```
POST /posts
Body: { "content": "Hello!", "media_ids": [] }
Response 201: { "post_id": "p123", "created_at": "..." }

GET /feed?limit=20&cursor=p900
Response 200: {
  "posts": [{ "post_id": "p123", "author": {...}, "content": "...", "likes": 42 }],
  "next_cursor": "p103"
}
```

## Scaling & Trade-offs

- **Feed cache size limit**: cap at 500 entries per user; older entries evicted (LRU)
- **Inactive users**: don't pre-populate feeds; build on login from scratch
- **Media**: store in CDN-backed blob storage; post only stores URLs
- **Thundering herd on login**: stagger fanout workers; use jitter on cache warm-up

## Key Decisions & Why

| Decision | Reason |
|---|---|
| Hybrid push/pull | Avoids write amplification from celebrities while keeping reads fast |
| Redis sorted set for feed | Fast range reads by score (timestamp); easy to cap size |
| Cursor pagination | Stable under concurrent inserts; no offset drift |
| Skip inactive users in fanout | Saves Redis memory; rebuild on demand |

## What Mid-to-Senior Candidates Often Miss

- Celebrity problem (fanout on write doesn't work for 10M followers)
- Feed cache size cap (unbounded Redis → OOM)
- Skipping inactive users during fanout
- Cursor-based vs. offset pagination (offset breaks under inserts)
- Ranking beyond pure chronological order