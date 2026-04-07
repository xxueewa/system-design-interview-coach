# Distributed Monitoring System

## Problem Statement
Design a monitoring system that collects metrics, logs, and traces from thousands of services,
stores them efficiently, and supports alerting and dashboards (like Datadog or Prometheus+Grafana).

## Requirements

**Functional**
- Collect metrics (counters, gauges, histograms) from services via push or pull
- Store time-series data with high write throughput
- Query metrics for dashboards (range queries, aggregations)
- Alert when a metric crosses a threshold
- Collect and search logs; support distributed traces

**Non-Functional**
- Scale: millions of metric series, billions of data points/day
- Write latency: ingest < 1s lag; query p99 < 5s for 24h range
- Retention: 30 days at full resolution; 1 year at 1-minute downsampling
- Availability: monitoring must survive monitored service failures

## High-Level Design

```
Services / Hosts
    │  push (StatsD/OpenTelemetry) OR pull (Prometheus scrape)
    ▼
Collector Fleet (stateless agents)
    │
    ▼
Kafka (metrics stream)
    │
    ├─► Time-Series DB (InfluxDB / Prometheus TSDB / Thanos)
    ├─► Log Store (Elasticsearch / Loki)
    └─► Trace Store (Jaeger / Tempo)

Query Layer
    ├─► Dashboard Service (Grafana)
    └─► Alerting Engine → PagerDuty / Slack
```

## Core Components

### Collector
- Receives metrics via UDP (StatsD) or HTTP (OpenTelemetry)
- Aggregates locally (1s buckets) to reduce downstream write amplification
- Adds metadata: host, service, region, environment tags
- Batches and ships to Kafka

### Time-Series DB

Data point: `(metric_name, tags, timestamp, value)`

Storage strategy:
- **Write path**: append-only WAL → in-memory chunks → flush to disk (compressed blocks)
- **Read path**: chunks indexed by label set; time-range index for fast scan
- **Compression**: delta encoding for timestamps + XOR encoding for float values (Gorilla compression)
- **Downsampling**: background job rolls up raw data to 1-min, 1-hour aggregates after retention window

### Alerting Engine
- Evaluates alert rules on a schedule (e.g., every 30s)
- Uses sliding window over TSDB: `avg(cpu) over 5m > 0.9`
- Deduplicates alerts (don't re-page if already firing)
- Routes to on-call via PagerDuty; sends context (graph snapshot, runbook link)

### Log Store
- Structured logs shipped via Fluentd/Filebeat → Kafka → Elasticsearch / Loki
- Index by service, timestamp, log level
- Full-text search + field filters (e.g., `service=auth level=error`)

### Distributed Tracing
- Services instrument with OpenTelemetry SDK; emit spans with trace_id
- Collector stitches spans into traces
- Stored in Jaeger or Tempo (object storage backend)
- Query: find slow traces, traces with errors, traces for a specific user

## Data Model (TSDB)

```
Series:  metric_name + label_set (e.g., http_requests_total{service="api",region="us-east"})
Chunk:   [ (t0, v0), (t1, v1), ... ] compressed, 2h window
Index:   label_name → label_value → series_ids
         time_range → chunk_file_offsets
```

## Cardinality Problem

High-cardinality labels (e.g., `user_id`, `request_id`) explode the number of series.
- **Rule**: only tag with low-cardinality dimensions (service, host, region, status_code)
- **Enforcement**: reject metrics with new label combinations above a threshold
- **Workaround for high-cardinality**: store in logs or traces, not metrics

## Scaling & Trade-offs

- **Kafka as buffer**: absorbs burst writes; decouples collectors from TSDB write speed
- **Federation**: each region runs its own Prometheus; global Thanos layer aggregates cross-region
- **Long-term storage**: compact old data to object storage (S3); query via Thanos or Cortex
- **Multi-tenancy**: separate label namespace per team; enforce write quotas

## API Design

```
POST /metrics/ingest
Body: OpenTelemetry protobuf (batch of metric points)
Response 204

POST /query/range
{
  "query": "rate(http_requests_total[5m])",
  "start": 1712000000,
  "end":   1712086400,
  "step":  "60s"
}
Response 200: { "data": { "resultType": "matrix", "result": [...] } }

POST /alerts
{
  "name": "HighCPU",
  "expr": "avg(cpu_usage) > 0.9",
  "for": "5m",
  "notify": ["pagerduty:team-infra"]
}
```

## Key Decisions & Why

| Decision | Reason |
|---|---|
| Kafka between collector and TSDB | Absorbs bursts; allows multiple consumers (metrics, logs) |
| Gorilla compression | 10x compression ratio for time-series float data |
| Downsampling for retention | Full-resolution data is expensive; 1-min rollups satisfy most queries |
| Separate stores for metrics/logs/traces | Different access patterns; metrics need fast range scans; logs need full-text |

## What Mid-to-Senior Candidates Often Miss

- Cardinality explosion from high-cardinality labels (user_id, request_id in tags)
- Collector-side aggregation before Kafka (reduces write amplification by 100x)
- Downsampling strategy for long-term retention
- Alert deduplication (avoid alert storm during an incident)
- Monitoring the monitoring system (need separate health check for the collector fleet)