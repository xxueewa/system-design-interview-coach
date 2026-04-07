# Web Crawler

## Problem Statement
Design a distributed web crawler that discovers and downloads web pages starting from a set
of seed URLs, following links to build a large-scale index of the web (or a domain subset).

## Requirements

**Functional**
- Accept seed URLs; recursively fetch and parse linked pages
- Store page content and extracted URLs
- Respect `robots.txt` and crawl-delay directives
- Avoid re-crawling unchanged pages (use ETags / Last-Modified)
- Support crawl scope: full web or limited to specific domains

**Non-Functional**
- Scale: billions of URLs; crawl rate of millions of pages/day
- Politeness: max 1 request/sec per domain (avoid overloading target servers)
- Deduplication: never store or re-crawl the same content twice
- Extensibility: pluggable parser for HTML, PDF, images

## High-Level Design

```
Seed URLs
    │
    ▼
URL Frontier (Priority Queue)
    │
    ▼
Fetcher Workers ──► DNS Resolver Cache
    │
    ├─► robots.txt Cache → skip if disallowed
    │
    ▼
Raw Content Store (S3)
    │
    ▼
Parser Workers
    ├─► Extract links → URL Frontier (deduplicated)
    └─► Extract content → Content Index / Search Engine
```

## Core Components

### URL Frontier
- Priority queue sorted by priority score (PageRank estimate, freshness)
- Back queue per domain to enforce politeness (one queue per host)
- Stored in Redis sorted sets or a dedicated queue DB

### Deduplication

**URL dedup**: Bloom filter to check if a URL has been seen before.
- False positive rate ~1% acceptable (miss a URL occasionally rather than re-crawl everything)
- Back this with a persistent set (Redis or DB) for ground truth

**Content dedup**: Simhash fingerprint of page content.
- Near-duplicate detection (catches pages differing only in ads/timestamps)
- Store fingerprint; skip if Hamming distance < threshold

### Fetcher
- Worker pool (hundreds of threads or async coroutines)
- Per-domain rate limiter (token bucket, 1 req/s per domain)
- Follows redirects (max depth 5)
- Handles timeouts (connect: 5s, read: 30s)
- Caches DNS lookups (TTL-respecting local cache)

### robots.txt Cache
- Fetch and parse once per domain; cache for 24h
- Block crawl if path matches Disallow rule for user-agent

### Parser
- Extracts all `<a href>` links
- Normalizes URLs: lowercase scheme+host, remove fragments, resolve relative paths
- Extracts page title, text, metadata for indexing

## Data Model

```
url_metadata table:
  url_hash       CHAR(64) PK   -- SHA-256 of normalized URL
  url            TEXT
  status         ENUM(pending, fetched, failed)
  last_fetched   TIMESTAMP
  etag           VARCHAR(128)
  content_hash   CHAR(64)      -- Simhash for dedup
  priority       FLOAT
  crawl_depth    INT
```

## URL Prioritization

- Fresh pages: recently updated (ping/sitemap hints)
- High-authority pages: high inbound link count (PageRank proxy)
- User-submitted: e.g., newly indexed site submitted via Search Console

## Scaling & Trade-offs

**Distributed fetchers**: shard URL frontier by domain hash → each worker owns a domain partition,
enforcing politeness naturally without cross-worker coordination.

**Storage**: raw HTML → S3; parsed/structured data → Elasticsearch or BigTable.

**Re-crawl scheduling**: use exponential backoff for frequently unchanged pages;
increase frequency for high-update pages (news sites).

**DNS bottleneck**: shared DNS cache across workers; rate-limit DNS queries per resolver.

## API Design

```
POST /crawl/seed
Body: { "urls": ["https://example.com"], "scope": "domain" }
Response 202: { "job_id": "abc123" }

GET /crawl/jobs/{job_id}/status
Response 200: { "urls_discovered": 1200000, "urls_fetched": 980000, "errors": 1200 }
```

## Key Decisions & Why

| Decision | Reason |
|---|---|
| Bloom filter for URL dedup | O(1) check, memory-efficient for billions of URLs |
| Simhash for content dedup | Catches near-duplicates, not just exact copies |
| Per-domain back queue | Enforces politeness without global lock |
| robots.txt cache | Avoids re-fetching on every request to the domain |
| S3 for raw content | Cheap, durable; separates storage from compute |

## What Mid-to-Senior Candidates Often Miss

- Politeness (crawl-delay, robots.txt) — without this, you get IP-banned
- DNS caching — DNS lookup latency kills throughput at scale
- Content deduplication with Simhash (not just exact hash)
- URL normalization before dedup (http vs https, trailing slash, etc.)
- Re-crawl strategy (not everything needs to be crawled at the same frequency)