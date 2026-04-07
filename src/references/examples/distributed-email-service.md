# Distributed Email Service

## Problem Statement
Design a distributed email service (like Gmail) that handles sending, receiving, storing, and
searching billions of emails across hundreds of millions of users.

## Requirements

**Functional**
- Send emails to recipients (internal and external)
- Receive inbound emails via SMTP
- Organize emails: inbox, sent, drafts, folders, labels
- Search emails by sender, subject, body, date
- Attachment support (up to 25MB)
- Spam filtering

**Non-Functional**
- Scale: 1B users; 100B emails stored; 100M emails sent/day
- Storage: average email 75KB → 7.5PB total
- Latency: inbox load < 500ms; send < 2s; search < 1s
- Deliverability: minimize emails landing in spam at recipients
- Availability: 99.99% for reading; slightly lower for sending

## High-Level Design

```
                   ┌──────── External SMTP Servers ────────┐
                   │                                        │
Sender ──SMTP──► Inbound Mail Server                 Outbound Mail Server ──SMTP──► Recipient
                   │                                        ▲
                   ▼                                        │
             Spam Filter                           Send Queue (Kafka)
                   │                                        │
                   ▼                                        │
             Message Store ◄──────────────── Mail Service API
                   │                                        │
             Search Index                           Web/App Client
```

## Sending Email

```
1. Client: POST /send {to, subject, body, attachments}
2. Mail Service API:
   a. Validate recipients, check rate limits
   b. Render MIME message
   c. Store attachment files in blob storage (S3); email body in message store
   d. Enqueue to Kafka (send queue)
3. SMTP Worker (Kafka consumer):
   a. Resolve MX record for recipient domain
   b. Connect via SMTP (with TLS)
   c. Deliver; retry with exponential backoff on failure
   d. Update send status (sent, bounced, deferred)
4. Update sent folder; trigger delivery receipt if configured
```

## Receiving Email

```
1. External SMTP connects to our inbound SMTP server (port 25)
2. SMTP handshake; receive MIME message
3. Spam filter pipeline:
   - SPF / DKIM / DMARC verification
   - Content-based spam score (ML model)
   - IP reputation check
4. If ham: deliver to user's inbox (write to Message Store)
5. If spam: deliver to spam folder
6. Send DSN (delivery status notification) back to sender
```

## Message Store

- Store email metadata and body separately
- Metadata (headers, labels) in DB for fast list/filter queries
- Body + attachments in blob storage (S3)

```
messages:
  message_id    BIGINT PK (Snowflake)
  user_id       BIGINT
  thread_id     BIGINT        -- groups replies
  from_addr     VARCHAR
  to_addrs      JSON          -- array of recipients
  subject       VARCHAR(1000)
  body_url      VARCHAR       -- S3 URL of MIME body
  snippet       VARCHAR(200)  -- first 200 chars for inbox list
  has_attachment BOOL
  labels        JSON          -- ["inbox", "work"]
  is_read       BOOL
  is_starred    BOOL
  sent_at       TIMESTAMP
  received_at   TIMESTAMP

user_labels:
  user_id       BIGINT
  label_id      BIGINT
  name          VARCHAR
  color         VARCHAR
```

## Threading

- Group messages by subject (strip "Re:", "Fwd:") + common recipients
- Or use `In-Reply-To` header (standard email threading)
- Thread ID assigned on first message; replies get same thread_id
- Inbox shows threads (like Gmail), not individual messages

## Search

- Full-text index on subject + snippet in Elasticsearch
- Body content indexed via async pipeline after delivery
- Metadata filters (from, to, has_attachment, is_starred) in Elasticsearch as structured fields
- Large-scale: partition Elasticsearch index by user_id range

## Attachment Storage

- Attachments stored in S3, referenced by URL in message record
- Deduplication: hash attachment content; if already in S3, reuse (saves storage for forwarded attachments)
- Virus scan before storing (ClamAV or cloud-based AV API)
- Max 25MB per email enforced at ingestion

## Spam Filtering

```
SPF:   check sender's IP against domain's authorized IPs
DKIM:  verify email signature matches domain's public key
DMARC: policy for what to do on SPF/DKIM failures (reject, quarantine, none)
Content: TF-IDF + neural classifier trained on labeled spam/ham
IP reputation: check sending IP against blocklists (Spamhaus, etc.)
```

Score each dimension (0-1); weighted sum → spam if > 0.7 threshold.

## API Design

```
POST /mail/send
Body: { "to": ["alice@example.com"], "subject": "Hi", "body": "...", "attachments": [...] }
Response 202: { "message_id": "m123", "queued": true }

GET /mail/inbox?label=inbox&page_token=...&limit=50
Response 200: {
  "threads": [{ "thread_id": "t1", "subject": "...", "snippet": "...", "unread": true }],
  "next_page_token": "..."
}

GET /mail/threads/{thread_id}
Response 200: { "messages": [...] }

POST /mail/search
Body: { "query": "from:alice invoice", "date_range": { "after": "2024-01-01" } }
Response 200: { "messages": [...] }
```

## Scaling & Trade-offs

- **Message DB sharding**: shard by `user_id` — each user's mail is independent; no cross-shard joins
- **Read replicas**: inbox list + search go to replicas; writes to primary
- **SMTP retry**: exponential backoff with max 72h retry window (RFC 5321); give up and send bounce
- **Storage tiering**: recent emails on SSD-backed storage; archive (>1 year) on S3 Glacier

## Key Decisions & Why

| Decision | Reason |
|---|---|
| Separate body in S3 | Bodies are large blobs; DB stores only metadata (small, indexable) |
| Kafka send queue | Decouples API from SMTP delivery; allows retry without blocking client |
| Snippet in DB | Avoids fetching body from S3 just to display inbox list |
| Thread grouping | Reduces inbox clutter; matches user mental model |
| SPF/DKIM/DMARC | Industry standard for sender verification and deliverability |

## What Mid-to-Senior Candidates Often Miss

- Separate metadata (DB) from body/attachments (blob storage)
- Threading logic (In-Reply-To header, not just subject matching)
- Attachment deduplication by content hash
- SMTP retry policy (72-hour window per RFC; must not block indefinitely)
- Deliverability infrastructure: SPF, DKIM records for your sending domain