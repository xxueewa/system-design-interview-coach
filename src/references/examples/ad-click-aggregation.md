# Ad Click Aggregation System

## Problem Statement
Design a system that ingests billions of ad click events per day and produces real-time and
historical aggregated metrics (clicks per ad, per campaign, per time window) used for billing
and analytics dashboards.

## Requirements

**Functional**
- Ingest click events: {ad_id, user_id, timestamp, country, device_type}
- Real-time aggregation: clicks per ad per minute (last 5 minutes)
- Historical aggregation: hourly/daily totals per ad, campaign, country
- Query API for dashboards (filter by ad_id, time range, dimensions)
- Deduplication: filter duplicate clicks (bots, accidental double-clicks)

**Non-Functional**
- Scale: 1B clicks/day = ~12K clicks/sec; spikes at 100K/sec
- Latency: real-time counts available within 1 minute
- Accuracy: ≤0.1% error on click counts (billing-grade)
- Durability: raw events retained for 7 days for reprocessing

## High-Level Design

```
Ad Server → Click Event (Kafka) → Stream Processor (Flink/Spark) → Aggregated Store (ClickHouse / Redis)
                  │
                  └─► Raw Event Store (S3) → Batch Processor (nightly reconciliation)
```

## Ingestion

- Ad server publishes to Kafka: `{ ad_id, user_id, ip, timestamp, country, device }`
- Kafka partitioned by `ad_id` → events for same ad processed by same worker → natural aggregation
- Kafka retention: 7 days (allows full reprocessing on pipeline failure)

## Deduplication

Two types:
1. **Same-session duplicate**: user clicks same ad twice in 10s (accidental double-click)
   - Deduplicate using a Redis Bloom filter: `{user_id}_{ad_id}_{10s_bucket}`; TTL 10s
2. **Bot/invalid traffic**: high click rate from same IP, no conversion
   - Rule-based filters (>20 clicks/min from same IP = invalid)
   - ML model running offline; flag IDs for exclusion

## Stream Processing (Real-Time)

```
Flink pipeline:
  1. Source: Kafka consumer (ad_id-partitioned)
  2. Dedup: check Bloom filter; skip duplicates
  3. Window: tumbling 1-minute window per ad_id
  4. Aggregate: count clicks, sum by country/device
  5. Sink: write windowed results to Redis (real-time dashboard)
         + write to ClickHouse (analytics store)

Redis key: clicks:{ad_id}:{minute_bucket}
Value: { total: 420, us: 300, uk: 120 }
TTL: 10 minutes (only needed for recent windows)
```

## Batch Processing (Historical)

- Nightly Spark job reads raw events from S3 (7-day retention)
- Recomputes hourly/daily totals; fills gaps from stream processing outages
- Results written to ClickHouse analytical tables
- Reconciliation: compare stream output vs. batch output; alert if >0.1% discrepancy

## Aggregation Storage

### ClickHouse (analytical queries)
```sql
ad_clicks_hourly:
  ad_id        BIGINT
  campaign_id  BIGINT
  hour         DATETIME
  country      VARCHAR(2)
  device_type  ENUM
  click_count  BIGINT
  PRIMARY KEY (ad_id, hour, country)
  PARTITION BY toYYYYMMDD(hour)
```

ClickHouse chosen for:
- Columnar storage → fast aggregations over large time ranges
- Sub-second queries on billions of rows
- Native time-series query support

### Redis (real-time window)
- Last 5 minutes of per-ad click counts for live dashboards
- Sorted sets for leaderboards: `ZREVRANGE top_ads:2024-04-07T12:00 0 9`

## Query API

```
GET /ads/{ad_id}/clicks
  ?start=2024-04-01&end=2024-04-07&granularity=hour&breakdown=country
Response 200: {
  "ad_id": "a123",
  "data": [
    { "hour": "2024-04-01T00:00", "country": "US", "clicks": 12000 },
    ...
  ]
}

GET /campaigns/{campaign_id}/clicks?granularity=day
GET /ads/top?limit=10&window_minutes=5  # real-time leaderboard from Redis
```

## Watermark and Late Events

- Network delays mean events can arrive late (up to 30s delay)
- Flink watermark: allow up to 30s of late arrivals before closing a window
- Late arrivals after window close → update batch store directly (not real-time)
- Dashboard shows real-time count as "approximate"; reconciled count updated next hour

## Scaling & Trade-offs

- **Kafka partition count**: use `ad_id % N` → 1 partition per top-N ads at peak; scale partitions as needed
- **Hot ad problem**: viral ad may get 100K clicks/sec → shard by `(ad_id, user_id % 10)` for aggregation, then merge
- **Flink checkpointing**: checkpoint every 30s to durable storage (S3) → resume without data loss on failure
- **Billing vs. analytics**: billing uses batch (exact); dashboards use stream (approximate, fast)

## Key Decisions & Why

| Decision | Reason |
|---|---|
| Kafka partitioned by ad_id | Co-located events for same ad; natural aggregation |
| Bloom filter dedup | Fast, memory-efficient; prevents double-count on billing |
| Lambda architecture (stream + batch) | Stream for real-time; batch for correctness and backfill |
| ClickHouse for analytics | Sub-second aggregation on billions of rows |
| Watermarks for late events | Correctness over completeness; configurable tolerance |

## What Mid-to-Senior Candidates Often Miss

- Hot ad problem (viral ad causes one Kafka partition to be a bottleneck)
- Late event handling with watermarks (windows must stay open briefly for stragglers)
- Lambda architecture reconciliation (stream results are approximate; batch is ground truth)
- Billing-grade deduplication (Bloom filter alone isn't enough — need persistent dedup store)
- ClickHouse partitioning by date (avoids full table scans for time-range queries)