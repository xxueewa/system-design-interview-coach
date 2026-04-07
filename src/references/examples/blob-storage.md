# Blob Storage

## Problem Statement
Design a distributed blob (object) storage system (like Amazon S3) that stores and serves
arbitrary binary objects (files, images, videos, backups) reliably at massive scale.

## Requirements

**Functional**
- Upload and download objects identified by a bucket + key
- List objects in a bucket with prefix filtering
- Delete objects
- Support object versioning and lifecycle policies (auto-delete after N days)

**Non-Functional**
- Scale: billions of objects; exabytes of data
- Durability: 11 nines (99.999999999%) — no data loss
- Availability: 99.99% for reads; slightly lower for writes is acceptable
- Throughput: large file uploads/downloads (multi-GB) at high bandwidth

## High-Level Design

```
Client
  │  upload/download (HTTPS)
  ▼
API Gateway / Load Balancer
  ├─► Metadata Service → Metadata DB (MySQL + Redis)
  └─► Data Service
        ├─► Data Nodes (store actual chunks)
        └─► Erasure Coding Engine
```

## Upload Flow

```
1. Client sends PUT /buckets/{bucket}/objects/{key}
2. API Gateway authenticates, checks bucket policy
3. Metadata Service generates object_id, splits object into chunks (e.g., 64MB each)
4. For each chunk:
   a. Data Service routes to data nodes (based on consistent hashing)
   b. Apply erasure coding: split into 6 data + 3 parity shards
   c. Write shards to 9 different nodes (across racks/AZs)
5. On all acks: Metadata Service records object metadata
6. Return ETag (MD5 or SHA-256 of object) to client
```

## Download Flow

```
1. Client sends GET /buckets/{bucket}/objects/{key}
2. Metadata Service returns chunk locations
3. Data Service fetches chunks from nodes (parallel reads)
4. Reassemble chunks → stream to client
```

## Data Durability: Erasure Coding

- **Replication** (3x): simple, fast recovery; 200% storage overhead
- **Erasure Coding (8+4 or 6+3)**: tolerate loss of any 3 (or 4) nodes; ~50% overhead
- S3-equivalent systems use erasure coding for cold/warm storage to reduce cost

```
Object split into 6 data shards + 3 parity shards (Reed-Solomon)
Any 6 of 9 shards sufficient to reconstruct object
Shards spread across different AZs → survives entire AZ failure
```

## Metadata Service

```
objects:
  object_id      BIGINT PK
  bucket_id      INT
  object_key     VARCHAR(1024)
  size_bytes     BIGINT
  etag           CHAR(64)
  version_id     BIGINT
  storage_class  ENUM(standard, infrequent, archive)
  created_at     TIMESTAMP
  expires_at     TIMESTAMP   -- NULL for no expiry
  deleted_at     TIMESTAMP   -- soft delete

object_chunks:
  object_id      BIGINT
  chunk_index    INT
  chunk_id       UUID        -- points to physical data
  PRIMARY KEY (object_id, chunk_index)

chunk_locations:
  chunk_id       UUID
  node_id        INT
  shard_index    INT         -- which erasure shard
```

## Data Node

- Stores chunks as flat files on local disk (XFS or ext4)
- Each chunk file named by chunk_id (UUID)
- Local index (RocksDB) maps chunk_id → file path + offset
- Checksum stored per chunk; verified on read (detect bit rot)

## Multi-Part Upload

For large files (>5GB):
```
1. POST /buckets/{b}/objects/{k}/multipart → returns upload_id
2. PUT  /buckets/{b}/objects/{k}/multipart/{upload_id}/parts/{part_num}  (parallel)
3. POST /buckets/{b}/objects/{k}/multipart/{upload_id}/complete
```
Each part is a chunk; final step assembles metadata only (no data copy).

## Versioning

- Every overwrite creates a new version (new object_id, same key)
- Latest version served by default; specify version_id to get old version
- Lifecycle policy: delete versions older than 30 days via background job

## API Design

```
PUT /buckets/{bucket}/objects/{key}
Headers: Content-Length, Content-Type, x-checksum-sha256
Response 200: { "etag": "abc123", "version_id": "v7" }

GET /buckets/{bucket}/objects/{key}?version_id=v5
Response 200: (object bytes stream)

DELETE /buckets/{bucket}/objects/{key}
Response 204

GET /buckets/{bucket}/objects?prefix=photos/2024&limit=100&cursor=...
Response 200: { "objects": [{ "key": "...", "size": 1024, "etag": "..." }], "next_cursor": "..." }
```

## Scaling & Trade-offs

- **Metadata DB sharding**: shard by `bucket_id` hash; hot buckets get their own shard
- **CDN**: serve reads for public objects via CDN edge nodes (S3 + CloudFront pattern)
- **Storage tiers**: Standard → Infrequent Access → Glacier; lifecycle policy moves objects down
- **Heartbeat + rebalancer**: data nodes send heartbeats; on node failure, rebalancer triggers shard recovery

## Key Decisions & Why

| Decision | Reason |
|---|---|
| Erasure coding over 3x replication | Same durability at half the storage cost |
| Separate metadata and data services | Independent scaling; metadata is small but hot |
| Chunk-based storage | Enables parallel uploads, partial reads, and deduplication |
| Consistent hashing for data nodes | Minimal redistribution on node join/leave |
| Checksums per chunk | Detects silent bit rot; ensures data integrity |

## What Mid-to-Senior Candidates Often Miss

- Erasure coding (saying "replicate 3x" is expensive and insufficient for exabyte scale)
- Multipart upload for large files (single TCP connection can't saturate bandwidth)
- Checksum validation on both write and read (bit rot is real at scale)
- Metadata DB as a separate scaling concern from data nodes
- Soft deletes + versioning interaction (delete marker, not physical removal)