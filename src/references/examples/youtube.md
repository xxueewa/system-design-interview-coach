# YouTube (Video Streaming Platform)

## Problem Statement
Design a video streaming platform (like YouTube) where users can upload videos, which are
transcoded and served to viewers at multiple resolutions with low buffering.

## Requirements

**Functional**
- Upload videos (up to 10GB)
- Transcode to multiple formats and resolutions (360p, 720p, 1080p, 4K)
- Stream video with adaptive bitrate (ABR)
- Search videos by title/tags
- Like, comment, subscribe
- Recommendations feed

**Non-Functional**
- Scale: 500 hours of video uploaded per minute; 1B daily views
- Upload: processing pipeline complete within 10 minutes for <1h videos
- Streaming: buffering < 2s; recover from poor network gracefully
- Durability: no video loss after upload complete

## High-Level Design

```
Upload Path:
  Creator → Upload Service → Raw Video Store (S3)
                → Transcoding Pipeline (async) → Processed Video Store (S3)
                → CDN origin pull

View Path:
  Viewer → CDN Edge → Video Segments (HLS/DASH)
```

## Video Upload Flow

```
1. Creator requests upload URL:
   POST /videos/initiate → { upload_url, video_id }

2. Creator uploads raw video directly to S3 via pre-signed URL (bypasses app servers)

3. S3 upload event → SQS/Kafka → Transcoding Orchestrator

4. Orchestrator:
   a. Extract metadata (duration, resolution, codec)
   b. Split video into GOP-aligned chunks (~10s each)
   c. Dispatch chunk transcoding jobs to worker fleet (parallel)
   d. Each worker: transcode chunk to all target resolutions
   e. Re-assemble into HLS segments (.ts files + .m3u8 manifest)
   f. Upload to CDN origin (S3)

5. Update video metadata: status = "ready"
```

## Transcoding Workers

- Stateless containers (FFmpeg-based); horizontally scalable
- Jobs in SQS; each worker pulls and processes one chunk
- GPU workers for 4K/HDR; CPU workers for 360p/720p
- Spot instances to reduce cost (jobs are idempotent, retryable)

## Adaptive Bitrate Streaming (ABR)

- Video encoded at multiple bitrates: 400Kbps, 1Mbps, 2.5Mbps, 8Mbps
- Segmented into 4-10 second chunks per bitrate
- HLS manifest (.m3u8) lists all available renditions
- Player monitors download speed and buffer level; switches rendition dynamically

```
master.m3u8
  ├── 360p/segment_%d.ts  (400Kbps)
  ├── 720p/segment_%d.ts  (1Mbps)
  └── 1080p/segment_%d.ts (2.5Mbps)
```

## CDN Strategy

- Videos served from CDN (CloudFront / Akamai) — edge nodes cache segments globally
- Long cache TTL for video segments (immutable; content-addressed by chunk hash)
- CDN pull from S3 origin on first request; subsequent requests served from edge
- Popular videos: pre-warm CDN edge caches after transcoding completes

## Data Model

```
videos:
  video_id       BIGINT PK
  uploader_id    BIGINT
  title          VARCHAR(200)
  description    TEXT
  status         ENUM(uploading, processing, ready, failed)
  duration_s     INT
  view_count     BIGINT
  like_count     BIGINT
  created_at     TIMESTAMP
  thumbnail_url  VARCHAR

video_files:
  video_id       BIGINT
  resolution     ENUM(360p, 720p, 1080p, 4k)
  bitrate_kbps   INT
  manifest_url   VARCHAR
  storage_bytes  BIGINT
  PRIMARY KEY (video_id, resolution)
```

## View Count (Approximate)

- Exact counts require distributed counters — expensive at 1B views/day
- Use **approximate counting**: batch view events in Kafka; count job aggregates per minute
- Store aggregate in Redis; flush to DB periodically
- Accept eventual consistency (count may lag by seconds/minutes)

## Search

- Video metadata indexed in Elasticsearch: title, description, tags, transcript (via speech-to-text)
- Query with BM25; boost by view_count and recency
- Autocomplete via edge n-gram index

## Recommendations

- Offline: collaborative filtering on view history → candidate videos
- Online: re-rank candidates by predicted CTR (click-through rate)
- Store recommendations per user in Redis (pre-computed); refresh every 30 minutes

## API Design

```
POST /videos/initiate
Response 201: { "video_id": "v123", "upload_url": "https://s3..." }

GET /videos/{id}
Response 200: { "title": "...", "manifest_url": "...", "thumbnail": "..." }

POST /videos/{id}/views
Body: { "watch_seconds": 120 }
Response 204

GET /search?q=cooking+pasta&sort=relevance&page=1
Response 200: { "results": [...] }
```

## Scaling & Trade-offs

- **Upload via pre-signed URL**: bypasses app servers; scales to 500 uploads/min trivially
- **Parallel chunk transcoding**: 1h video at 10s/chunk = 360 jobs → 360 workers → done in minutes
- **Hot video caching**: top 1% of videos served entirely from CDN; S3 never hit
- **Multi-region**: replicate processed videos to regional S3 buckets; CDN origin picks nearest

## Key Decisions & Why

| Decision | Reason |
|---|---|
| Direct S3 upload via pre-signed URL | App servers don't touch raw video bytes |
| Chunk-level parallel transcoding | Reduces total transcoding time from hours to minutes |
| HLS over RTMP | Standard, CDN-compatible, supports ABR |
| CDN for video delivery | Handles global scale; reduces origin load |
| Approximate view counts | Exact counts under 1B views/day require too much coordination |

## What Mid-to-Senior Candidates Often Miss

- Pre-signed upload URLs (uploading through app servers won't scale)
- GOP-aligned chunking for transcoding (random splits break video decoding)
- ABR and how the manifest file enables dynamic quality switching
- CDN cache warming for viral videos (cold CDN = thundering herd on origin)
- View count approximate counting strategy (exact counting is a distributed systems problem)