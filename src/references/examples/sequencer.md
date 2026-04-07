# Sequencer (Distributed ID Generator)

## Problem Statement
Design a service that generates globally unique, roughly time-ordered IDs at high throughput
for use as primary keys in distributed databases, event logs, and message queues.

## Requirements

**Functional**
- Generate unique 64-bit IDs
- IDs should be roughly sortable by time (useful for pagination and range scans)
- Support multiple services and data centers

**Non-Functional**
- Throughput: millions of IDs/sec across the system
- Latency: ID generation < 1ms p99
- Availability: no single point of failure
- No central coordination required (or minimized)

## Approaches

### Option 1: UUID v4
- Randomly generated 128-bit ID
- Pro: trivially distributed, no coordination
- Con: not time-ordered, large (128 bits), poor DB index locality

### Option 2: Database Auto-Increment
- Pro: simple, perfectly ordered
- Con: SPOF, doesn't scale horizontally

### Option 3: Snowflake (Recommended)

```
| 41 bits: timestamp (ms since epoch) | 10 bits: machine ID | 12 bits: sequence |
```

- Generates up to 4096 IDs/ms per node
- IDs are time-ordered
- No coordination needed between nodes
- 41-bit timestamp → ~69 years before overflow (use custom epoch, e.g., 2020-01-01)

### Option 4: Ticket Server
- Centralized DB with auto-increment hands out batches of IDs
- Good for small scale; central DB becomes bottleneck

## Snowflake Implementation

```
function generateId(machineId):
    now = currentTimestampMs()

    if now == lastTimestamp:
        sequence = (sequence + 1) & 0xFFF  # 12-bit mask
        if sequence == 0:
            # Sequence exhausted — wait for next millisecond
            now = waitNextMs(lastTimestamp)
    else:
        sequence = 0

    lastTimestamp = now

    return ((now - EPOCH) << 22) | (machineId << 12) | sequence
```

## Machine ID Assignment

- **Zookeeper**: nodes register at startup, receive a unique shard number (0–1023)
- **Config file**: static assignment per deployment (simpler, works for fixed fleets)
- **IP-based**: derive machine ID from IP last 2 octets (risky if IPs are reused)

## High-Level Design

```
Service A ──┐
Service B ──┼──► Snowflake ID Generator (per-node, no coordination)
Service C ──┘         └─ Zookeeper (machine ID allocation only)
```

Each application node runs its own generator. No cross-node calls for ID generation.

## API Design

```
# If running as a dedicated microservice:
GET /id
Response 200: { "id": "7041652783448064000" }

GET /ids?count=100
Response 200: { "ids": ["...", "..."] }
```

Or embed as a library — preferred for latency-critical paths.

## Handling Clock Skew

- NTP can move the clock backward, causing duplicate timestamps
- **Detection**: if `now < lastTimestamp`, either wait or throw an error
- **Guard**: reject requests when clock is more than a threshold behind (e.g., 5ms)
- **Monitoring**: alert on clock drift > 1ms across ID nodes

## Scaling & Trade-offs

- **Multi-datacenter**: allocate 5 bits to DC ID, 5 bits to machine ID within DC (total 10 bits)
- **Library vs service**: library has zero network overhead; service centralizes monitoring
- **Sequence overflow**: at 4096 IDs/ms, a single node can do ~4M IDs/sec — sufficient for most services
- **Custom epoch**: pick a recent fixed point to maximize timestamp range

## Key Decisions & Why

| Decision | Reason |
|---|---|
| Snowflake over UUID | Time-ordered; 64-bit fits in a long; better DB index locality |
| Per-node generation | No coordination = no bottleneck, no SPOF |
| Zookeeper for machine ID | One-time allocation at startup; not on the hot path |
| Custom epoch | Extends range and reduces wasted bits |

## What Mid-to-Senior Candidates Often Miss

- Clock skew / backward NTP adjustment causing duplicate IDs
- Sequence rollover within the same millisecond (need to wait for next ms)
- Machine ID reuse after node replacement (Zookeeper lease expiry handles this)
- 41-bit timestamp overflow in ~69 years (mention custom epoch to push it out)
- Embedding as a library vs. running as a service (trade-offs on latency vs. ops simplicity)