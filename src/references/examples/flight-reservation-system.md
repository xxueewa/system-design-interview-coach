# Flight Reservation System

## Problem Statement
Design a flight reservation system (like Expedia or airline.com) where users can search
for flights, select seats, and book tickets with strong consistency to prevent double-selling seats.

## Requirements

**Functional**
- Search available flights by origin, destination, date
- View seat map and select specific seats
- Reserve and pay for tickets (one or more passengers)
- Cancel and refund bookings
- Airlines manage seat inventory and pricing

**Non-Functional**
- Consistency: no double-selling a seat (must be strongly consistent)
- Scale: 10M searches/day; 1M bookings/day
- Latency: search < 1s; booking < 3s
- Availability: search is AP; booking is CP (prefer consistency over availability)

## High-Level Design

```
Client
  ├─► Search Service → Flight Index (Elasticsearch) + Fare Cache
  ├─► Seat Service → Seat Inventory DB (MySQL)
  └─► Booking Service → Booking DB (MySQL) + Payment Service
                              └─ Distributed Lock (Redis / DB row lock)
```

## Flight Search

- Elasticsearch index: `{ flight_id, origin, destination, departure_dt, airline, available_seats, base_fare }`
- Updated asynchronously from inventory DB via CDC (5-10s eventual consistency)
- Multi-hop search: A→C via A→B + B→C (graph traversal, pre-computed connections stored in search index)
- Fare rules: taxes, baggage fees computed at display time; locked at booking time

## Seat Inventory Model

```
flights:
  flight_id    BIGINT PK
  flight_no    VARCHAR      -- e.g., "AA123"
  origin       CHAR(3)      -- IATA code
  destination  CHAR(3)
  departure_dt TIMESTAMP
  arrival_dt   TIMESTAMP
  aircraft     VARCHAR

seats:
  seat_id      BIGINT PK
  flight_id    BIGINT
  seat_number  VARCHAR      -- e.g., "14A"
  class        ENUM(economy, business, first)
  status       ENUM(available, held, booked)
  hold_expires TIMESTAMP    -- null unless status=held
  version      INT          -- optimistic lock version

bookings:
  booking_id   BIGINT PK (Snowflake)
  user_id      BIGINT
  status       ENUM(pending, confirmed, cancelled)
  total_fare   DECIMAL
  pnr          VARCHAR(6)   -- passenger name record, 6-char code
  created_at   TIMESTAMP

booking_seats:
  booking_id   BIGINT
  seat_id      BIGINT
  passenger_name VARCHAR
  passport_no  VARCHAR (encrypted)
```

## Booking Flow

```
1. User selects seats (e.g., 14A, 14B)

2. Hold seats (15-minute TTL):
   BEGIN TRANSACTION;
   SELECT id, status, version FROM seats WHERE seat_id IN (14A, 14B) FOR UPDATE;
   IF any status != 'available': ROLLBACK → "seat no longer available"
   UPDATE seats SET status='held', hold_expires=NOW()+900, version=version+1
     WHERE seat_id IN (14A, 14B);
   INSERT INTO bookings (status='pending', ...);
   COMMIT;
   Return { booking_id, hold_expires }

3. User submits payment (up to 15 min)

4. On payment success:
   BEGIN TRANSACTION;
   UPDATE seats SET status='booked' WHERE seat_id IN (...) AND status='held';
   UPDATE bookings SET status='confirmed', pnr=generate_pnr();
   COMMIT;
   Trigger confirmation email + boarding pass generation

5. On payment failure or hold expiry:
   UPDATE seats SET status='available', hold_expires=NULL WHERE seat_id IN (...);
   UPDATE bookings SET status='cancelled';
```

## Expiry Job

- Runs every minute via cron or scheduled job
- `UPDATE seats SET status='available' WHERE status='held' AND hold_expires < NOW()`
- Prevents orphaned holds from blocking inventory

## Pricing and Fare Buckets

- Airlines use fare classes (Y, B, M, Q, ...) with different prices for the same seat class
- Cheaper fare classes have limited seats; sold out → next bucket at higher price
- Dynamic pricing engine adjusts bucket availability based on demand + days to departure
- Price locked at booking creation; does not change during the hold period

## Distributed Lock for High-Demand Seats

For extreme cases (e.g., last seat on a popular flight):
- `SELECT FOR UPDATE` at DB level is sufficient for single-region
- Multi-region: use Redis Redlock or route all writes for a `flight_id` to one primary region

## API Design

```
GET /flights?origin=JFK&dest=LAX&date=2024-06-01&passengers=2
Response 200: {
  "flights": [
    { "flight_id": "f1", "departure": "08:00", "arrival": "11:30",
      "available_seats": 24, "price_from": 199 }
  ]
}

GET /flights/{id}/seats
Response 200: { "seat_map": [{ "seat": "14A", "class": "economy", "status": "available", "price": 219 }] }

POST /bookings
Body: { "flight_id": "f1", "seats": ["14A", "14B"], "passengers": [...] }
Response 201: { "booking_id": "b99", "status": "pending", "hold_expires": "...", "fare": 438 }

POST /bookings/{id}/pay
Body: { "payment_token": "tok_visa_123" }
Response 200: { "status": "confirmed", "pnr": "XKQZ7T" }

DELETE /bookings/{id}
Response 200: { "refund_amount": 350 }
```

## Cancellation and Refund

- Airline cancellation policies: fully refundable, partially refundable, non-refundable
- On cancel: release seats (`status = 'available'`), compute refund per policy, initiate payment reversal
- Partial cancel (one of two passengers): release one seat; adjust booking fare

## Scaling & Trade-offs

- **Search**: Elasticsearch for read-heavy search; stale by a few seconds is acceptable
- **Booking DB**: sharded by `flight_id` — all seat operations for a flight stay on one shard
- **Read replicas**: seat map reads go to replica; writes (holds, bookings) always to primary
- **Multi-leg bookings**: atomic hold across two flights; if second flight fails, release first

## Key Decisions & Why

| Decision | Reason |
|---|---|
| SELECT FOR UPDATE | Prevents concurrent seat selection race condition |
| Hold pattern (15-min) | Users need time to fill passenger details and pay |
| Shard by flight_id | All contention for a flight on one DB shard; no distributed locking |
| Elasticsearch for search | Full multi-dimensional search; stale by seconds is acceptable |
| PNR generation at confirmation | PNR is public identifier; only generate on successful payment |

## What Mid-to-Senior Candidates Often Miss

- Hold expiry job (without this, failed payments permanently block seats)
- Shard by flight_id (put all contention for one flight on one machine)
- Multi-leg atomicity (must hold all legs or none — partial hold is a bad UX)
- Fare bucket dynamics (not all seats have the same price; cheapest bucket sells out first)
- Cancellation policies stored per booking, not looked up at cancel time