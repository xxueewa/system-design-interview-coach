# Search System

## Problem Statement
Design a full-text search system (like Elasticsearch or Google Search for a product catalog)
that indexes documents and serves keyword and faceted search queries with low latency.

## Requirements

**Functional**
- Index documents with structured and free-text fields
- Full-text search with ranking (BM25 / TF-IDF)
- Faceted filtering (e.g., category, price range, rating)
- Autocomplete / typeahead suggestions
- Near real-time indexing (new documents searchable within seconds)

**Non-Functional**
- Scale: 1B documents; 10K queries/sec
- Query latency: p99 < 200ms
- Index freshness: < 5s from write to searchable
- Availability: 99.9%; prefer stale results over errors

## High-Level Design

```
Document Sources (DB, events)
    │
    ▼
Indexing Pipeline
    ├─ Document Processor (extract, transform, normalize)
    ├─ Tokenizer / Analyzer (stemming, stopwords, lowercase)
    └─ Index Writer → Inverted Index Shards

Query Path:
Client → Query Service → Query Parser → Scatter-Gather → Shard Results → Ranker → Response
```

## Inverted Index

Core data structure: `term → [(doc_id, positions, frequency), ...]`

```
"iphone" → [doc:42 (freq:3, pos:[1,5,9]), doc:87 (freq:1, pos:[2]), ...]
"apple"  → [doc:42 (freq:2, ...), doc:103 (freq:1, ...)]
```

- Stored as immutable segments (like SSTables)
- New documents go into a small in-memory buffer; flushed to disk segments periodically
- Background merge of small segments into larger ones (reduces query fan-out)

## Document Processing Pipeline

```
1. Extract:   pull fields from source (title, description, price, category)
2. Normalize: lowercase, unicode normalization
3. Tokenize:  split on whitespace/punctuation
4. Analyze:   stemming ("running" → "run"), synonym expansion ("phone" → "smartphone")
5. Index:     update inverted index for each token
6. Store:     store document fields for retrieval (forward index / doc store)
```

## Sharding Strategy

- Shard by `doc_id % N` → each shard holds a subset of documents
- Query is broadcast to all shards (scatter); results are merged and re-ranked (gather)
- Replication: each shard has 1 primary + 2 replicas (fault tolerance + read throughput)

## Ranking (BM25)

```
score(q, d) = Σ IDF(t) × (tf(t,d) × (k1+1)) / (tf(t,d) + k1×(1 - b + b×|d|/avgdl))

where:
  IDF(t)  = log((N - df(t) + 0.5) / (df(t) + 0.5))
  tf(t,d) = term frequency in doc d
  |d|     = document length
  avgdl   = average document length
  k1, b   = tuning constants (typically 1.2, 0.75)
```

Custom signals layered on top: freshness, click-through rate, seller rating.

## Autocomplete

- **Trie**: in-memory prefix trie on top-K query completions; returned in microseconds
- **Edge n-gram index**: index "iph", "ipho", "iphon", "iphone" at index time
- Backed by Redis sorted set: `ZRANGEBYLEX suggestions:{prefix} "[i" "[i\xff"` (lexicographic range)
- Update top-K completions daily from query logs

## Faceted Search

- Facets (category, brand, price_range) stored as indexed fields
- Pre-aggregated counts via **DocValues** (columnar storage per field)
- Example: `?q=iphone&category=electronics&price=100-500` → apply filter post-BM25

## Near Real-Time Indexing

```
Source DB → CDC (Debezium) → Kafka → Index Consumer
    └─ Consumer writes to in-memory buffer
    └─ Buffer flushed to new segment every 1-2s
    └─ Segment made searchable (refreshed) every 1s
```

## API Design

```
POST /search
{
  "query": "wireless headphones",
  "filters": { "category": "electronics", "price": { "gte": 50, "lte": 300 } },
  "sort": "relevance",
  "page": 1,
  "page_size": 20
}
Response 200: {
  "total": 1523,
  "hits": [{ "doc_id": "...", "title": "...", "score": 3.4 }],
  "facets": { "brand": [{"sony": 42}, {"bose": 31}] }
}

GET /suggest?q=ipho&limit=5
Response 200: { "suggestions": ["iphone 15", "iphone case", "iphone charger"] }
```

## Scaling & Trade-offs

- **Hot terms**: very common terms ("the", "a") are excluded (stopwords) to avoid huge posting lists
- **Caching**: cache results for frequent identical queries (Redis, 30s TTL)
- **Read replicas**: route search queries to replicas; primary only for index writes
- **Multi-tenancy**: separate indices per tenant or shared index with tenant_id field filter

## Key Decisions & Why

| Decision | Reason |
|---|---|
| Inverted index | O(1) lookup per term; built for full-text search |
| BM25 over TF-IDF | Better length normalization; state of the art for keyword search |
| Immutable segments + merge | Efficient writes without locking; read path always consistent |
| Scatter-gather | Parallelizes search across all shards for low latency |
| CDC → Kafka for indexing | Decoupled, near-real-time, survives downstream failures |

## What Mid-to-Senior Candidates Often Miss

- Segment merge strategy (without merge, too many small segments slow down queries)
- Index refresh interval (data is not immediately searchable after write — there's a lag)
- Stopwords and high-frequency term handling (posting lists can be millions long)
- Facet count accuracy vs. performance (exact counts are expensive across distributed shards)
- Autocomplete stored separately from the main search index