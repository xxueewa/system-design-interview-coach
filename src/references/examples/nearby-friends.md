# Nearby Friends

## Problem Statement
Design a "nearby friends" feature (like Facebook's Nearby Friends) where users can opt in to
share their location and see which friends are within a configurable radius in near real-time.

## Requirements

**Functional**
- Users opt in to share their location
- App sends location updates every 30 seconds
- User can see friends within X km (user-configurable, e.g., 1–50km)
- Friends list is refreshed approximately every 30 seconds
- Show approximate distance (not exact location) to preserve privacy

**Non-Functional**
- Scale: 1B users; 10% opted-in = 100M active users sharing location
- Update throughput: 100M updates / 30s ≈ 3.3M location writes/sec
- Read latency: friend list update < 1s p99
- Privacy: store only approximate location; expire quickly

## High-Level Design

```
User App (GPS update every 30s)
    │
    ▼
Location Update Service (WebSocket or HTTP)
    │
    ▼
Kafka (location events)
    ├─► Location Store (Redis — ephemeral, TTL 60s)
    └─► Fanout Service
            ├─ Look up user's opted-in friends
            └─ Push nearby friend updates to each friend's WebSocket channel

User App (receives nearby friend list via WebSocket push)
```

## Location Update Flow

```
1. App sends: { user_id, lat, lng, timestamp }
2. Location Update Service:
   a. Validate user is opted-in
   b. Encode location as geohash (precision 4 → ~40km cell)
   c. Write to Redis: SET loc:{user_id} {geohash, lat, lng, ts} EX 60
   d. Publish event to Kafka: { user_id, geohash, timestamp }

3. Fanout Service (Kafka consumer):
   a. Look up user's friends who are opted-in (from Social Graph Service)
   b. For each opted-in friend:
      - Fetch friend's location from Redis
      - Compute distance
      - If within threshold: send update to friend's WebSocket channel
```

## Geohash for Proximity

- Geohash precision 6 → ~1.2km × 0.6km cells (good for 1km radius)
- For 50km radius → precision 4 (~40km cells) + 8 neighbors
- Store user's geohash in Redis; find friends in same or adjacent geohash cells

```
Nearby check:
  my_geohash = geohash(my_lat, my_lng, precision=5)
  neighbor_cells = [my_geohash] + get_neighbors(my_geohash)
  nearby_users = SCAN loc:* WHERE geohash IN neighbor_cells  # simplified
```

## WebSocket Architecture

- Each app maintains a persistent WebSocket to a channel server
- Channel servers are stateless; WebSocket session mapping stored in Redis:
  `ws:{user_id} → server_id`
- Fanout service looks up `ws:{friend_id}`, routes message to correct channel server
- Channel server delivers to the WebSocket connection

## Location Store (Redis)

```
Key:   loc:{user_id}
Value: { lat: 37.77, lng: -122.41, geohash: "9q8yy", ts: 1712000000 }
TTL:   60s (auto-expire if user stops sending updates)
```

No persistent storage of exact locations. Only approximate geohash logged for analytics.

## Privacy Controls

- Exact lat/lng never shown to friends — only distance bucket ("within 1 km", "within 5 km")
- Opt-in required; can pause sharing without logging out
- Auto-pause if app goes to background and location permission not "always on"
- "Ghost mode": user sees others but is not visible themselves

## Fanout Optimization

- **Challenge**: popular user with 5000 opted-in friends → 5000 lookups + pushes per update
- **Solution**: only fan out to friends who are **also active** (sent update in last 60s)
- Filter: check `EXIST loc:{friend_id}` in Redis before doing distance computation
- Result: fanout size ≈ active opted-in friends within region, not total friend count

## Social Graph Service

- Returns list of opted-in friends for a given user
- Cached aggressively (friend lists change slowly vs. location updates)
- Cache TTL: 5 minutes; invalidated on friend add/remove or opt-out

## Data Model

```
user_settings:
  user_id         BIGINT PK
  sharing_enabled BOOL
  radius_km       INT DEFAULT 10

# No persistent location table — ephemeral in Redis only
```

## API Design

```
WebSocket: ws://api/nearby-friends/stream

Client sends (every 30s):
  { "type": "location_update", "lat": 37.77, "lng": -122.41 }

Server pushes:
  {
    "type": "nearby_friends_update",
    "friends": [
      { "user_id": "u42", "display_name": "Alice", "distance_km": 1.2 },
      { "user_id": "u87", "display_name": "Bob", "distance_km": 4.7 }
    ]
  }

REST:
PATCH /settings/location
Body: { "sharing_enabled": true, "radius_km": 5 }
```

## Scaling & Trade-offs

- **3.3M writes/sec**: Redis can handle this with a cluster; Kafka buffers spikes
- **Geohash partitioning**: Kafka partitioned by geohash → regional consumers handle nearby users
- **Inactive user filtering**: Redis TTL auto-expires stale locations; no cleanup job needed
- **Battery impact**: 30s interval is aggressive; back off to 60s when user is stationary (detected by accelerometer)

## Key Decisions & Why

| Decision | Reason |
|---|---|
| Redis with TTL for location | Ephemeral; auto-cleanup; fast reads |
| WebSocket for push updates | Real-time without polling overhead |
| Geohash for proximity | Fast cell-based lookup; avoids distance calc for all users |
| Fanout only to active friends | Reduces fan-out by 90%+ in practice |
| No persistent exact location | Privacy; regulatory compliance (GDPR) |

## What Mid-to-Senior Candidates Often Miss

- TTL on location store (stale locations must expire, not accumulate)
- Active friend filter before fanout (otherwise 5000 pushes per update is unacceptable)
- Geohash cell boundary problem (friends just across the cell boundary are missed without neighbor cells)
- Privacy: storing or displaying exact coordinates vs. approximate distance
- Battery optimization (adaptive update interval based on motion state)