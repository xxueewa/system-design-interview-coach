# Chat System

## Problem Statement
Design a real-time chat system (like WhatsApp or Slack) supporting 1-on-1 and group messaging,
message persistence, online presence, and delivery receipts.

## Requirements

**Functional**
- 1-on-1 messaging and group chats (up to 500 members)
- Message delivery receipts: sent, delivered, read
- Online/offline presence
- Message history and pagination
- Media sharing (images, files) via links
- Push notifications when offline

**Non-Functional**
- Scale: 500M DAU; 100B messages/day
- Latency: real-time message delivery < 100ms p99 for online users
- Ordering: messages within a conversation must be ordered
- Durability: no message loss

## High-Level Design

```
Client (mobile/web)
    │  WebSocket (persistent connection)
    ▼
WebSocket Gateway (stateless connection servers)
    │
    ├─► Chat Service (message fanout, group logic)
    │       └─► Message Queue (Kafka)
    │               └─► Message Storage (Cassandra)
    │
    ├─► Presence Service (online/offline)
    │       └─► Redis (user_id → last_heartbeat)
    │
    └─► Notification Service (push for offline users)
```

## Connection Management

- Clients hold a persistent **WebSocket** connection to one gateway node
- Gateway mapping stored in Redis: `ws_session:{user_id} → gateway_node_id`
- On disconnect, gateway marks session gone; presence service updates status

## Message Flow (1-on-1)

```
1. Sender → WebSocket → Gateway A
2. Gateway A → Chat Service: {msg_id, from, to, content, timestamp}
3. Chat Service:
   a. Write message to Cassandra (async, via Kafka for durability)
   b. Lookup recipient's gateway from Redis
   c. If recipient online: push message to Gateway B → WebSocket → recipient
   d. If offline: enqueue push notification
4. Return ack to sender: {msg_id, status: "sent"}
5. Recipient ACKs delivery → update status to "delivered"
6. Recipient opens chat → update status to "read"
```

## Group Message Flow

```
1. Sender → Chat Service
2. Chat Service looks up all members of group (from GroupMembers table)
3. Fan out message to each online member's gateway
4. Write one copy of message to storage
5. Track per-member delivery/read receipts
```

## Message Storage (Cassandra)

```
messages:
  conversation_id  UUID      PARTITION KEY  -- ensures messages co-located
  msg_id           BIGINT    CLUSTERING KEY DESC  -- Snowflake ID (time-ordered)
  sender_id        UUID
  content          TEXT
  msg_type         ENUM(text, image, file)
  created_at       TIMESTAMP

conversations:
  conversation_id  UUID   PK
  type             ENUM(direct, group)
  created_at       TIMESTAMP

group_members:
  group_id         UUID
  user_id          UUID
  joined_at        TIMESTAMP
  PRIMARY KEY (group_id, user_id)
```

Cassandra chosen for:
- High write throughput
- Range reads by `msg_id` within a partition (conversation) — efficient pagination
- No joins needed; denormalized

## Presence Service

- Clients send a heartbeat ping every 30s over WebSocket
- Presence service writes `{user_id: last_seen_ts}` to Redis with 60s TTL
- Online = last_seen within 60s; Offline = key expired
- Query: `GET presence:{user_id}` → timestamp or nil

## Message Ordering & ID

- Use Snowflake IDs as `msg_id` (time-ordered, monotonic within a server)
- Within a conversation: order by `msg_id` descending
- No global ordering guarantee across conversations (not needed)

## Delivery & Read Receipts

```
msg_receipts:
  msg_id       BIGINT
  user_id      UUID
  status       ENUM(delivered, read)
  updated_at   TIMESTAMP
```

- Status shown as double-tick (delivered) / blue double-tick (read)
- Receipt update pushed to sender via WebSocket

## API Design

```
WebSocket events (JSON):
  → send_message:   { type, to, content, client_msg_id }
  ← new_message:    { msg_id, from, content, timestamp }
  ← receipt_update: { msg_id, status, updated_at }
  ← presence:       { user_id, online, last_seen }

REST:
GET /conversations/{id}/messages?before={msg_id}&limit=50
Response 200: { "messages": [...], "has_more": true }
```

## Scaling & Trade-offs

- **Gateway scaling**: stateless; add nodes behind load balancer; Redis stores session mapping
- **Group fan-out**: for large groups (500 members), fan-out is expensive — batch with Kafka
- **Media**: upload directly to S3 via pre-signed URL; store only URL in message
- **Message sync**: clients store messages locally; on reconnect, fetch `since_msg_id` from server

## Key Decisions & Why

| Decision | Reason |
|---|---|
| WebSocket over HTTP polling | Real-time, bidirectional; no polling overhead |
| Cassandra for messages | High write throughput; range reads by conversation |
| Redis for presence | Sub-ms reads; TTL-based expiry handles offline detection |
| Snowflake IDs | Time-ordered without central coordination |
| Kafka for fan-out | Decouples delivery from storage; handles group burst |

## What Mid-to-Senior Candidates Often Miss

- Gateway session mapping in Redis (how does server know which node to push to?)
- Presence using heartbeat TTL (not a DB flag that must be explicitly updated)
- At-least-once delivery with client-side dedup (msg_id dedup on receive)
- Large group fan-out bottleneck (500 member group = 500 WebSocket pushes synchronously)
- Message sync on reconnect (client must pull messages missed while offline)