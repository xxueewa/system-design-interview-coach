# Hotel Reservation System

## Problem Statement
Design a hotel reservation system (like Booking.com) that allows users to search for
available hotels, view room inventory, and complete bookings without double-booking.

## Requirements

**Functional**
- Search hotels by location, date range, guests, amenities
- View room availability and pricing for selected dates
- Reserve a room (hold → confirm → pay)
- Cancel reservation
- Hotel managers update room inventory and pricing

**Non-Functional**
- Scale: 10M DAU; 1M reservations/day
- Consistency: no double-booking (two users cannot book the same room for overlapping dates)
- Read-heavy: search queries >> booking transactions (100:1)
- Latency: search < 500ms; booking < 2s

## High-Level Design

```
Client
  ├─► Search Service → Elasticsearch (hotel/room index) + Availability Cache
  ├─► Availability Service → Room Inventory DB (MySQL)
  └─► Booking Service → Reservation DB (MySQL) + Payment Service
```

## Search Flow

```
1. User: GET /hotels?city=NYC&check_in=2024-05-01&check_out=2024-05-03&guests=2
2. Search Service queries Elasticsearch for matching hotels (location, amenities, rating)
3. For each hotel result: fetch available room types and prices from Availability Cache (Redis)
4. Merge and rank results (price, rating, distance) → return top 20
```

Search is eventually consistent — availability cache may lag by seconds, shown as "starting from $X".

## Availability Check

```
Room availability for a date range:
  total_rooms - SUM(reservations where check_in < check_out AND check_out > check_in AND status != 'cancelled')

Cached in Redis:
  Key: avail:{hotel_id}:{room_type}:{date}
  Value: available_count (integer)
  TTL: 30s (refresh from DB)
```

## Booking Flow (Avoiding Double-Booking)

### Problem: Race Condition
Two users concurrently book the last available room for the same dates.

### Solution: Optimistic Locking + DB Transaction

```sql
-- Step 1: Select with version check
SELECT id, available_count, version
FROM room_inventory
WHERE hotel_id = 123 AND room_type = 'deluxe' AND date = '2024-05-01'
FOR UPDATE;  -- pessimistic lock for booking

-- Step 2: Check and decrement
IF available_count > 0:
  UPDATE room_inventory
  SET available_count = available_count - 1, version = version + 1
  WHERE hotel_id = 123 AND room_type = 'deluxe' AND date = '2024-05-01'
    AND version = <read_version>;  -- optimistic: fail if changed concurrently
  IF rows_updated == 0: RETRY or RETURN "sold out"

-- Step 3: Create reservation record (same transaction)
INSERT INTO reservations (user_id, hotel_id, room_type, check_in, check_out, status, ...)

COMMIT;
```

For date ranges: decrement `room_inventory` for each date in the range atomically.

### Hold → Pay → Confirm Pattern

```
1. Hold: reserve inventory for 15 minutes (status = 'pending'); user fills payment form
2. Pay:  charge payment method
3. Confirm: if payment succeeds → status = 'confirmed'; if fails or hold expires → release inventory

Expiry job: cron every minute, release holds older than 15 minutes
```

## Data Model

```
hotels:
  hotel_id    BIGINT PK
  name        VARCHAR
  city        VARCHAR
  lat, lng    DECIMAL
  rating      DECIMAL(2,1)

room_types:
  hotel_id    BIGINT
  room_type   VARCHAR  (e.g., standard, deluxe, suite)
  max_guests  INT
  base_price  DECIMAL

room_inventory:
  hotel_id        BIGINT
  room_type       VARCHAR
  date            DATE
  total_rooms     INT
  available_count INT
  version         INT     -- for optimistic locking
  PRIMARY KEY (hotel_id, room_type, date)

reservations:
  reservation_id  BIGINT PK (Snowflake)
  user_id         BIGINT
  hotel_id        BIGINT
  room_type       VARCHAR
  check_in        DATE
  check_out       DATE
  status          ENUM(pending, confirmed, cancelled)
  total_price     DECIMAL
  expires_at      TIMESTAMP   -- for pending holds
  created_at      TIMESTAMP
```

## Pricing

- Base price from `room_types`
- Dynamic pricing: weekend surcharge, demand multiplier, seasonal rates
- Stored as price rules in a separate table; computed at search time
- Price locked at reservation creation (not at display time)

## API Design

```
GET /hotels/search?city=NYC&check_in=2024-05-01&check_out=2024-05-03&guests=2
Response 200: { "hotels": [{ "id": "h1", "name": "...", "price_from": 199 }] }

POST /reservations
Body: { "hotel_id": "h1", "room_type": "deluxe", "check_in": "...", "check_out": "...", "user_id": "u99" }
Response 201: { "reservation_id": "r123", "status": "pending", "expires_at": "..." }

POST /reservations/{id}/pay
Body: { "payment_token": "tok_visa_123" }
Response 200: { "status": "confirmed" }

DELETE /reservations/{id}
Response 200: { "refund_amount": 199 }
```

## Scaling & Trade-offs

- **Read replicas**: search and availability queries go to read replicas; writes to primary
- **Elasticsearch**: updated asynchronously from DB via CDC; eventual consistency for search
- **Inventory sharding**: shard `room_inventory` by `hotel_id` — each hotel's inventory is independent
- **Overbooking**: some hotels intentionally allow 5% overbooking (airline model); configurable in `room_types`

## Key Decisions & Why

| Decision | Reason |
|---|---|
| Per-date inventory rows | Enables fine-grained availability; easy range decrement |
| SELECT FOR UPDATE | Prevents double-booking under concurrent requests |
| Hold pattern (15-min TTL) | User needs time to pay without losing room |
| Elasticsearch for search | Full-text + geospatial + faceted filtering |
| Optimistic locking + version | Detects concurrent modification without long lock holds |

## What Mid-to-Senior Candidates Often Miss

- Double-booking race condition (need `SELECT FOR UPDATE` or equivalent atomic decrement)
- Hold expiry (without this, failed payments permanently block inventory)
- Date-range inventory: must decrement every date in the range atomically (not just check-in date)
- Price lock at reservation time (not at display time — prices change between search and pay)
- Overbooking as a business configuration (separate from a system bug)