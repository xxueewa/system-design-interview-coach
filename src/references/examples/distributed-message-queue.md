# Distributed Message Queue

## Problem Statement
Design a distributed message queue (like Apache Kafka or Amazon SQS) that durably stores
messages published by producers and delivers them to consumers reliably and at scale.

## Requirements

**Functional**
- Producers publish messages to named topics
- Consumers subscribe to topics and receive messages
- At-least-once delivery (optional: exactly-once)
- Messages retained for a configurable period (e.g., 7 days)
- Consumer groups: multiple consumers share a topic's messages (each message to one consumer in group)
- Replay: consumers can seek to any offset and re-read messages

**Non-Functional**
- Throughput: millions of messages/sec per topic
- Latency: publish-to-consume latency < 100ms p99
- Durability: no message loss after ack
- Scalability: horizontal scaling by adding partitions

## High-Level Design

```
Producers → Broker Cluster → Consumer Groups
                │
                ├─ Partition 0: [msg0, msg1, msg2, ...]
                ├─ Partition 1: [msg0, msg1, ...]
                └─ Partition N: [...]

ZooKeeper / Metadata Service: topic config, partition leadership, consumer offsets
```

## Core Concepts

### Topic and Partitions
- A **topic** is a logical stream; divided into **partitions** for parallelism
- Each partition is an ordered, append-only log
- Producers write to a partition (by key hash, round-robin, or custom)
- Consumers in a group each own one or more partitions — messages distributed across group
- More partitions → more consumer parallelism

### Offset
- Each message in a partition has a monotonically increasing **offset** (like a log sequence number)
- Consumers track their position as a `(topic, partition, offset)` triple
- Consumer commits offset after processing → allows resumption after crash

## Broker Architecture

```
Each Broker:
  ├─ Write Path: append to partition log (sequential disk write → fast)
  ├─ Read Path:  sendfile() syscall for zero-copy delivery to consumers
  └─ Replication: leader broadcasts writes to follower replicas
```

### Log Storage

```
Partition log on disk:
  segment_000000000000.log     (messages 0–999999)
  segment_000000000000.index   (offset → byte offset mapping)
  segment_000001000000.log
  ...

Active segment: append-only writes
Old segments: immutable; served via index for random access by offset
Retention: delete segments older than 7 days or > configured size
```

### Zero-Copy Reads
- Consumer fetch: server calls `sendfile(fd, socket)` — kernel copies directly from page cache to NIC
- Avoids user-space copy; dramatically increases throughput for replay/catch-up

## Replication

- Each partition has 1 leader and N-1 followers (typically 2 followers, RF=3)
- Producer writes to leader; leader replicates to followers synchronously (for acks=all)
- Follower catches up via fetch loop (same as consumer protocol)
- On leader failure: ZooKeeper / controller elects a new leader from in-sync replicas (ISR)

```
acks options:
  acks=0:   fire and forget (fastest, may lose messages)
  acks=1:   leader acked (loses data if leader fails before replication)
  acks=all: all ISR acked (strongest durability guarantee)
```

## Consumer Groups

```
Topic: orders (3 partitions: P0, P1, P2)
Consumer Group A (3 consumers):
  Consumer 1 → P0
  Consumer 2 → P1
  Consumer 3 → P2

Consumer Group B (1 consumer):
  Consumer 1 → P0, P1, P2 (handles all partitions)
```

- Group coordinator (one broker) manages partition assignment
- On consumer join/leave: rebalance triggered (partitions reassigned across group)

## Offset Management

```
Consumer commits offsets to __consumer_offsets (internal topic):
  commit: { group_id, topic, partition, offset }

On crash/restart:
  consumer fetches last committed offset → resumes from there
  → may re-process messages between last commit and crash (at-least-once)

For exactly-once:
  → write output + commit offset in a single transaction (Kafka transactions API)
```

## API Design

```
# Producer
POST /topics/{topic}/messages
Body: { "key": "user_123", "value": "<base64 bytes>", "headers": {} }
Response 200: { "partition": 2, "offset": 10042 }

# Consumer (long-poll)
GET /topics/{topic}/partitions/{partition}/messages?offset=10000&limit=100&timeout_ms=1000
Response 200: {
  "messages": [{ "offset": 10000, "key": "...", "value": "...", "timestamp": ... }]
}

# Commit offset
POST /consumer-groups/{group}/offsets
Body: { "topic": "orders", "partition": 2, "offset": 10100 }
Response 204
```

## Scaling & Trade-offs

- **Throughput**: add partitions; each partition is a unit of parallelism
- **Retention**: disk is cheap; retain 7 days by default; consumers can replay
- **Compacted topics**: for event sourcing — keep only the latest value per key; older entries garbage collected
- **Backpressure**: if consumers are slow, messages accumulate in partition log; monitor consumer lag

## Key Decisions & Why

| Decision | Reason |
|---|---|
| Append-only log | Sequential writes are 100× faster than random writes on disk |
| Zero-copy sendfile | Eliminates user-space copy; critical for high-throughput replay |
| Consumer pulls (not push) | Consumer controls rate; no broker-side state per consumer |
| Partition key routing | Ensures ordering per key (e.g., all events for user_123 are in order) |
| acks=all for durability | Lose no messages even if the leader dies immediately after write |

## What Mid-to-Senior Candidates Often Miss

- Consumer pull model (not push) — broker doesn't track per-consumer state for delivery
- Offset commit timing (commit before processing = at-most-once; after = at-least-once)
- ISR (in-sync replica) concept — only ISR members are eligible to become leader
- Partition count can't decrease (only increase) — must plan partition count upfront
- Consumer group rebalance storm — adding/removing consumers triggers global rebalance