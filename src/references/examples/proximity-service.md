# Proximity Service

## Problem Statement
Design a proximity service (like Yelp's "find restaurants near me") that returns points of
interest (POIs) within a given radius of the user's current location.

## Requirements

**Functional**
- Search POIs within a radius (e.g., 500m–50km) from a lat/lng
- Filter by category (restaurant, hotel, gas station)
- Return basic info per POI (name, address, rating, distance)
- Business owners can add/update/delete their listings

**Non-Functional**
- Scale: 100M POIs; 1B queries/day
- Query latency: p99 < 100ms
- Read >> write (searches far outnumber business updates)
- Eventual consistency for business data updates is acceptable

## High-Level Design

```
Client (lat, lng, radius, category)
    │
    ▼
Location Search Service
    ├─► Geospatial Index (read replicas) → return POI IDs
    └─► Business Service → enrich with details (name, rating, photos)
                └─► Business DB (MySQL) + Cache (Redis)
```

## Geospatial Indexing

### Option A: Geohash (Recommended for simplicity)
- Encode lat/lng into a base-32 string of variable precision
- Precision: geohash length 6 → ~1.2km × 0.6km cell
- Nearby search = find all POIs in the same geohash cell + 8 neighbors

```
Geohash precision table:
  Length 4: ~40km × 20km
  Length 5: ~5km  × 5km
  Length 6: ~1.2km × 0.6km
  Length 7: ~150m × 150m
```

- Search radius 1km → geohash length 6
- Search radius 10km → geohash length 5
- Store `(geohash, poi_id)` in index table; look up by geohash prefix

### Option B: Quadtree
- Recursively subdivide map into quadrants until each cell has ≤ N POIs
- Good for dynamic, uneven POI density
- More complex to implement and update

### Option C: PostGIS / Spatial Index (R-Tree)
- Native geo queries in PostgreSQL; handles complex shapes
- Good for moderate scale; harder to shard

## Data Model

```
pois:
  poi_id        BIGINT PK
  name          VARCHAR(200)
  category      VARCHAR(100)
  address       TEXT
  lat           DECIMAL(9,6)
  lng           DECIMAL(9,6)
  geohash       VARCHAR(12)   -- indexed
  rating        DECIMAL(2,1)
  is_active     BOOL
  created_at    TIMESTAMP

poi_details:
  poi_id        BIGINT PK     -- 1:1 with pois; separated for hot/cold access
  phone         VARCHAR
  website       VARCHAR
  hours         JSONB
  photos        JSONB
```

## Query Flow

```
1. Client: GET /pois?lat=37.7749&lng=-122.4194&radius=2000&category=restaurant
2. Convert radius to geohash precision level (2km → length 5)
3. Compute geohash of center: e.g., "9q8yy"
4. Find 8 neighboring geohash cells
5. Query index: SELECT poi_id FROM poi_geohash WHERE geohash IN (9 cells)
6. Filter by exact distance (Haversine formula) to enforce circle boundary
7. Fetch details for matching poi_ids from Business Service (cached)
8. Sort by distance; paginate; return
```

## Distance Calculation

```python
# Haversine formula (approximate great-circle distance)
def haversine(lat1, lng1, lat2, lng2):
    R = 6371000  # Earth radius in meters
    φ1, φ2 = radians(lat1), radians(lat2)
    Δφ = radians(lat2 - lat1)
    Δλ = radians(lng2 - lng1)
    a = sin(Δφ/2)**2 + cos(φ1)*cos(φ2)*sin(Δλ/2)**2
    return 2 * R * asin(sqrt(a))
```

## Caching

- **POI details**: cached in Redis by `poi_id` (TTL 1 hour); updated on business write
- **Geohash search results**: cache full result set for popular `(geohash, category)` pairs (TTL 5 min)
- **No caching of geohash index itself**: kept in read replicas; fast index scans

## Write Path (Business Updates)

```
Business Owner → Admin API → Business Service → MySQL (primary)
    → Invalidate Redis cache for poi_id
    → Update geohash in poi_geohash index (if location changed)
    → Async: propagate to search read replicas
```

## API Design

```
GET /pois/search
  ?lat=37.7749&lng=-122.4194&radius=2000&category=restaurant&limit=20
Response 200: {
  "results": [
    { "poi_id": "p1", "name": "Pasta Palace", "distance_m": 350, "rating": 4.5 }
  ],
  "total": 38
}

POST /pois
Body: { "name": "...", "lat": ..., "lng": ..., "category": "..." }
Response 201: { "poi_id": "p99" }

PUT /pois/{id}
DELETE /pois/{id}
```

## Scaling & Trade-offs

- **Read replicas**: geohash index replicated across multiple DB replicas; search queries load-balanced
- **POI density**: high-density cities have geohash cells with thousands of POIs → add secondary filter
- **Multi-category**: if user wants all categories, run parallel queries per category, merge
- **Dynamic POIs**: ride-sharing pickup points, event venues → short TTL geohash cache

## Key Decisions & Why

| Decision | Reason |
|---|---|
| Geohash over R-tree | Simpler, works with any key-value or relational DB |
| 8-neighbor search | Handles edge case where target is near cell boundary |
| Haversine post-filter | Geohash cells are rectangles; radius is a circle |
| Separate poi_details table | Hot column (geohash/lat/lng) vs. cold (photos/hours) |
| Cache POI details | Business data changes rarely; read-heavy traffic |

## What Mid-to-Senior Candidates Often Miss

- 8-neighbor geohash fetch (not just the user's cell — misses nearby results at boundaries)
- Post-filter with Haversine (geohash cells are squares, not circles)
- Precision level selection (different radius → different geohash length)
- Cache invalidation on POI location update (geohash changes when lat/lng changes)
- Handling dense urban areas where a geohash cell returns thousands of results