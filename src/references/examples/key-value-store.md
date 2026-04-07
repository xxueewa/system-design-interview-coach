# Key-Value Store

## Problem Statement
Design a distributed key-value store (like DynamoDB or Redis) that supports get/put/delete
operations with configurable consistency, high availability, and horizontal scalability.

## Requirements

**Functional**
- `put(key, value)` — write or overwrite a key
- `get(key)` → value — read by key
- `delete(key)` — remove a key
- Keys and values are arbitrary byte strings (max 10 KB per value)

**Non-Functional**
- Scale: billions of keys, petabytes of data
- Latency: p99 < 10ms for reads and writes
- Availability: 99.99% uptime (AP system; eventual consistency acceptable)
- Durability: no data loss on node failure

## High-Level Design

```
Client
  └─ Coordinator Node (any node can be coordinator)
        ├─ Consistent Hashing Ring → select N replicas
        ├─ Write to W replicas (quorum write)
        └─ Read from R replicas (quorum read)

Each Storage Node:
  ├─ In-memory cache (hot keys)
  ├─ Write-ahead log (WAL) for durability
  ├─ LSM Tree (MemTable → SSTables on disk)
  └─ Bloom filter (fast negative lookups)
```

## Partitioning: Consistent Hashing

- All nodes sit on a virtual hash ring
- Each physical node has multiple virtual nodes (vnodes) to balance load
- Key maps to the first node clockwise on the ring
- Replication: key is replicated to the next N-1 nodes clockwise
- When a node joins/leaves, only its neighbors' key ranges are affected

## Replication & Consistency

| Parameter | Meaning |
|---|---|
| N | Total replicas per key |
| W | Minimum replicas to ack a write |
| R | Minimum replicas to read from |

- Strong consistency: W + R > N (e.g., N=3, W=2, R=2)
- High availability: W=1, R=1 (eventual consistency)
- Typical: N=3, W=2, R=2 — good balance

## Data Model (Storage Node)

```
MemTable (sorted, in-memory)
  └─ Flush when full → SSTable (immutable, sorted on disk)
       └─ Background compaction merges SSTables

WAL: append-only log per node for crash recovery
Bloom filter: per SSTable, skip disk reads for missing keys
```

## Conflict Resolution

- Use **vector clocks** to track causality across replicas
- On concurrent writes, keep both versions (siblings)
- Resolve at read time: last-write-wins (LWW) or application-level merge
- Tombstones for deletes (prevent deleted keys from reappearing via old replicas)

## API Design

```
PUT /keys/{key}
Body: { "value": "<base64>", "ttl_seconds": 3600 }
Response 200: { "version": "v3" }

GET /keys/{key}
Response 200: { "value": "<base64>", "version": "v3" }
Response 404: key not found

DELETE /keys/{key}
Response 204
```

## Failure Handling

**Hinted handoff**: if a replica is down, coordinator writes to a temporary node with a hint.
When the downed node recovers, the temporary node forwards the write.

**Anti-entropy / Merkle trees**: each node maintains a Merkle tree of its key space.
Nodes periodically compare trees to detect and repair divergence without scanning all keys.

**Gossip protocol**: nodes exchange state (membership, health) peer-to-peer — no single point of truth.

## Scaling & Trade-offs

- **Hot keys**: use client-side caching or add a dedicated in-memory tier (Redis in front)
- **Large values**: store value in blob storage, keep only a pointer in the KV store
- **TTL**: store expiry timestamp alongside value; lazy deletion on read + background GC
- **Multi-region**: active-active with async replication; accept eventual consistency across regions

## Key Decisions & Why

| Decision | Reason |
|---|---|
| LSM Tree over B-Tree | Write-optimized; sequential disk I/O |
| Consistent hashing | Minimal key redistribution on node changes |
| Gossip protocol | No single coordinator to fail |
| Bloom filters | Avoid expensive disk reads for missing keys |
| Vector clocks | Detect concurrent writes without coordination |

## What Mid-to-Senior Candidates Often Miss

- Consistent hashing with virtual nodes (plain hashing causes hotspots on node join/leave)
- WAL before MemTable flush (data loss on crash otherwise)
- Tombstones for deletes (deleted keys reappear from old replicas without them)
- Merkle trees for anti-entropy (without this, replica repair requires full scans)
- Bloom filters save disk I/O for negative lookups (common in read-heavy workloads)