# Real-Time Gaming Leaderboard

## Problem Statement
Design a real-time leaderboard for a competitive game that tracks player scores, supports
rank queries, and updates rankings in near real-time during live events (like a game tournament).

## Requirements

**Functional**
- Update a player's score after each game
- Query top-K players globally (e.g., top 100)
- Query a player's current rank and score
- Query players surrounding a given player (±10 positions around rank)
- Support multiple leaderboard types: global, regional, friends

**Non-Functional**
- Scale: 10M concurrent players; 10M score updates/min during peak
- Latency: rank query < 50ms; score update < 100ms
- Freshness: leaderboard updates within 5 seconds of a score change
- Availability: leaderboard reads must remain available even if writes are degraded

## High-Level Design

```
Game Server → Score Update → Message Queue (Kafka) → Score Processor
                                                            │
                                                     Redis Sorted Set (live leaderboard)
                                                            │
                                                     ← Client queries rank/top-K
                                          (periodic snapshot → MySQL for persistence)
```

## Core Data Structure: Redis Sorted Set

Redis Sorted Set is the perfect fit:
- `ZADD leaderboard {score} {player_id}` — O(log N) insert/update
- `ZRANK leaderboard {player_id}` — O(log N) rank query
- `ZREVRANGE leaderboard 0 99` — O(log N + K) top-100 query
- `ZREVRANGEBYSCORE` — range queries by score
- `ZREVRANK` — reverse rank (rank 1 = highest score)

```
Key:    leaderboard:global
Key:    leaderboard:region:us-east
Key:    leaderboard:friends:{user_id}
```

## Score Update Flow

```
1. Game server: player earns score → POST /scores { player_id, delta, game_id }
2. Score Service:
   a. Validate (authenticate game server, check for cheating heuristics)
   b. ZINCRBY leaderboard:global {delta} {player_id}
   c. Publish event to Kafka for persistence and analytics
3. Kafka consumer:
   a. UPDATE player_scores SET score = score + delta WHERE player_id = ...
   b. Update regional leaderboard if applicable
```

## Rank Query Flow

```
GET /leaderboard/rank/{player_id}
  1. ZREVRANK leaderboard:global {player_id} → returns 0-indexed rank
  2. ZSCORE leaderboard:global {player_id}   → returns current score
  3. Return { rank: rank+1, score: score }

GET /leaderboard/top?limit=100
  1. ZREVRANGE leaderboard:global 0 99 WITHSCORES
  2. Fetch display names for returned player_ids (bulk get from Redis or user service)
  3. Return ranked list

GET /leaderboard/around/{player_id}?window=10
  1. rank = ZREVRANK leaderboard:global {player_id}
  2. start = max(0, rank - 10), end = rank + 10
  3. ZREVRANGE leaderboard:global start end WITHSCORES
```

## Friends Leaderboard

- Maintained as a separate sorted set per user: `leaderboard:friends:{user_id}`
- On score update: for each of the player's friends (from social graph), update their friends leaderboard
- Challenge: player with 1000 friends → 1000 ZINCRBY calls on score update
- Optimization: use a shared global sorted set + friends list; compute friends leaderboard on read via ZMSCORE

```
Friends leaderboard (read-time computation):
  friend_ids = get_friends(user_id)  # from social graph, cached
  scores = ZMSCORE leaderboard:global friend_ids  # batch get scores
  rank locally by score and return
```

## Persistence Layer

```
player_scores (MySQL):
  player_id    BIGINT PK
  score        BIGINT
  games_played INT
  updated_at   TIMESTAMP

leaderboard_snapshots (for historical boards):
  snapshot_id   BIGINT PK
  leaderboard   VARCHAR   -- 'global', 'us-east', etc.
  player_id     BIGINT
  rank          INT
  score         BIGINT
  snapshot_at   TIMESTAMP

  INDEX (leaderboard, snapshot_at, rank)
```

Periodic snapshot (every 5 min): `ZREVRANGE` → bulk insert to `leaderboard_snapshots`.
This enables historical queries ("what was rank 1 yesterday?").

## Tie-Breaking

- When two players have the same score, secondary sort by `updated_at` ascending (earlier = better rank)
- Encode: `score_encoded = score * 10^10 + (MAX_TIMESTAMP - updated_at_unix)`
- Store `score_encoded` in Redis sorted set; display original score to user

## Handling Scale

### 10M concurrent players

- Redis sorted set handles 10M members efficiently (memory: ~100 bytes/member → ~1GB)
- Single Redis node supports ~100K ops/sec; cluster with 10 shards handles 1M ops/sec

### Multiple leaderboards

- Shard leaderboards: `leaderboard:global:shard:{hash(player_id) % 10}`
- Top-K query: run ZREVRANGE on each shard, merge and re-sort → global top-K

### Tournament leaderboards

- Short-lived sorted sets per tournament: `leaderboard:tournament:{tournament_id}`
- Expire automatically after tournament ends (SET with TTL)

## Anti-Cheat

- Score delta validated against server-side game state (game servers are authoritative)
- Anomaly detection: player scoring 10× their average → flag for review
- Rate limit score updates: max N score events per second per player

## API Design

```
POST /scores
Body: { "player_id": "p123", "delta": 500, "game_id": "g789" }
Response 200: { "new_score": 12500, "rank": 42 }

GET /leaderboard?type=global&limit=100
Response 200: { "entries": [{ "rank": 1, "player_id": "p1", "score": 99000 }] }

GET /leaderboard/rank/{player_id}?type=global
Response 200: { "rank": 42, "score": 12500, "percentile": 99.6 }

GET /leaderboard/around/{player_id}?window=10
Response 200: { "entries": [...], "my_rank": 42 }
```

## Key Decisions & Why

| Decision | Reason |
|---|---|
| Redis Sorted Set | O(log N) rank queries; built for this exact use case |
| ZINCRBY (atomic increment) | No read-modify-write race condition |
| MySQL for persistence | Redis is ephemeral; DB is ground truth for recovery |
| Friends leaderboard via ZMSCORE | Avoids maintaining per-user sorted set for each friend update |
| Score encoding for tie-breaking | Redis sorted sets sort by score only; encode secondary sort into score |

## What Mid-to-Senior Candidates Often Miss

- Tie-breaking (two players with same score need a deterministic rank)
- Friends leaderboard fan-out problem (1000 friends = 1000 writes per score update)
- Redis sorted set memory limits (plan for 10M members explicitly)
- Anti-cheat validation (scores must be server-authoritative, not client-reported)
- Periodic MySQL snapshot for recovery (Redis restart = leaderboard gone without persistence)