# Google Maps

## Problem Statement
Design a mapping service (like Google Maps) that provides map rendering, routing between
locations, real-time traffic, and ETA estimation.

## Requirements

**Functional**
- Display a map with zoom levels (tiles)
- Route between two points (driving, walking, transit)
- Real-time ETA with traffic
- Turn-by-turn navigation
- Search for places

**Non-Functional**
- Scale: 1B DAU; petabytes of map tile data
- Map tile latency: <100ms (mostly CDN-served)
- Route latency: <2s for complex inter-city routes
- Traffic data: updated every 30–60 seconds

## High-Level Design

```
Client
  ├─► Tile Service → CDN → Map Tile Store (S3)
  ├─► Route Service → Graph Engine (road network graph)
  ├─► Traffic Service → Real-time traffic aggregation
  └─► Search Service → POI search (proximity + text)
```

## Map Tiles

- World map divided into a tile grid at each zoom level
- Zoom 0: 1 tile (entire world); zoom 18: 68B tiles (street level)
- Tile coordinates: `(zoom, x, y)` — standard TMS/XYZ scheme
- Each tile is a 256×256 PNG/WebP image (vector tiles: PBF format)

```
Tile pipeline (offline):
  Raw geo data (OpenStreetMap, satellite imagery)
    → Projection (Mercator)
    → Tile generation (mapnik / tippecanoe)
    → Store tiles in S3: /tiles/{zoom}/{x}/{y}.png
    → Warm CDN edges globally
```

Tiles are immutable once generated; served with long Cache-Control (max-age=7d).

## Routing

### Data: Road Network Graph
- Nodes: intersections (lat/lng)
- Edges: road segments (distance, speed limit, road type, turn restrictions)
- Compressed and stored in memory on route servers (~100GB for US road network)

### Algorithm: Bidirectional Dijkstra with Contraction Hierarchies (CH)

- **Dijkstra** is too slow for global routing (billions of nodes)
- **Contraction Hierarchies**: pre-process graph by adding "shortcut" edges between high-importance nodes
- Reduces query time from O(V log V) to milliseconds even for cross-country routes
- Preprocessing done offline; updated when road data changes

### Traffic-Aware Routing

- Edge weights = `travel_time = distance / effective_speed`
- Effective speed = historical average speed × traffic factor
- Traffic factor updated every 30s from real-time probe data
- Routing server re-weights graph edges periodically

## Real-Time Traffic

```
Sources of traffic data:
  1. GPS probes from app users (anonymized locations every 15s)
  2. Partnerships with road sensors, telcos
  3. Incident reports from users

Pipeline:
  App GPS events → Kafka → Traffic Aggregator
    → Map matched to road segment (which road is this GPS trace on?)
    → Compute speed: segment_length / time_between_samples
    → Update segment speed in Redis (TTL 60s)
    → Route servers poll Redis every 30s for updated edge weights
```

### Map Matching
- GPS has error; must snap raw GPS coordinates to the road network
- Hidden Markov Model (HMM): most likely road segment sequence given noisy GPS trace
- Implemented on the traffic aggregation server

## ETA Estimation

```
ETA = Σ (segment_length / effective_speed) for all segments on route

Adjustments:
  + turn penalty (left turn across traffic = +15s)
  + traffic light delays
  + historical delay model for time-of-day
  + re-estimation every 30s during navigation using current position
```

## Turn-by-Turn Navigation

- Client downloads the planned route (list of maneuvers + waypoints)
- Client uses GPS + dead reckoning for positioning between GPS updates
- Every 30s: client sends current position → server recalculates ETA, optionally reroutes
- Reroute threshold: if user deviates >50m from route or a faster route appears

## Data Model

```
road_segments:
  segment_id     BIGINT PK
  start_node_id  BIGINT
  end_node_id    BIGINT
  length_m       FLOAT
  speed_limit    INT
  road_type      ENUM(highway, arterial, local)
  geometry       LINESTRING  -- encoded polyline

traffic_speed (Redis):
  Key: traffic:{segment_id}
  Value: { speed_mps: 12.5, updated_at: 1712345678 }
  TTL: 90s
```

## API Design

```
GET /tiles/{zoom}/{x}/{y}
Response 200: (PNG/PBF bytes, cached by CDN)

POST /route
{
  "origin": { "lat": 37.77, "lng": -122.41 },
  "destination": { "lat": 34.05, "lng": -118.24 },
  "mode": "driving",
  "departure_time": "now"
}
Response 200: {
  "duration_s": 22800,
  "distance_m": 614000,
  "steps": [{ "instruction": "Head north on Market St", "distance_m": 250 }],
  "polyline": "..."
}

GET /eta?origin=...&destination=...
Response 200: { "eta_seconds": 1800, "traffic": "moderate" }
```

## Scaling & Trade-offs

- **Tiles**: 99% of traffic served from CDN; S3 is cold origin only
- **Route servers**: in-memory graph; scale horizontally; each server holds full graph
- **Traffic updates**: Redis for sub-second reads; Kafka for durable event stream
- **Map updates**: re-run tile pipeline nightly for changed areas only (delta processing)

## Key Decisions & Why

| Decision | Reason |
|---|---|
| CDN for tiles | Tiles are static per zoom+coord; global cache is extremely effective |
| Contraction Hierarchies | Reduces route query from minutes to milliseconds at global scale |
| HMM for map matching | Handles GPS noise; essential for accurate traffic speed computation |
| Redis for traffic | Sub-second read; TTL-based staleness management |
| Bidirectional search | Halves search space vs. unidirectional Dijkstra |

## What Mid-to-Senior Candidates Often Miss

- Contraction Hierarchies (plain Dijkstra is too slow for global routes)
- Map matching for GPS probes (raw GPS → road segment is a non-trivial step)
- Tile pyramid structure and zoom levels (not one map image but billions of tiles)
- Re-estimation during active navigation (ETA must update as conditions change)
- Traffic data TTL / staleness (old traffic data is worse than no traffic data)