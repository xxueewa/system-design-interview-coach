# Notification System

## Problem Statement
Design a notification service that sends push notifications, SMS, and emails to millions of
users triggered by product events (e.g., order shipped, friend request, payment received).

## Requirements

**Functional**
- Support channels: push (iOS/Android), SMS, email
- Send triggered (event-based) and scheduled notifications
- Users can set preferences (opt-out per channel, quiet hours)
- Deduplication: never send the same notification twice

**Non-Functional**
- Scale: 10M+ notifications/day; peak 100K/sec for marketing blasts
- Latency: triggered notifications delivered within 5 seconds (p95)
- Reliability: at-least-once delivery; log failures for retry
- Observability: track delivery status per notification

## High-Level Design

```
Product Services (order, social, payment)
    │ event
    ▼
Notification Service API
    │
    ▼
Message Queue (Kafka, one topic per channel)
    ├─► Push Worker  → APNs (iOS) / FCM (Android)
    ├─► SMS Worker   → Twilio / SNS
    └─► Email Worker → SendGrid / SES

           └─► Notification Log (DB)
               └─► Retry Queue (dead-letter)
```

## Core Components

### Notification Service API
- Accepts event payloads from upstream services
- Looks up user preferences (opt-outs, quiet hours, language)
- Resolves delivery tokens (push tokens, email addresses, phone numbers)
- Publishes one message per channel to Kafka

### User Preference Store
- Redis for hot preference lookups (opted-out users, quiet hours)
- DB for source of truth (MySQL or DynamoDB)

### Channel Workers
- Stateless consumers on Kafka topics
- Call third-party provider (APNs, FCM, Twilio, SendGrid)
- On success: write `delivered` status to Notification Log
- On failure: write `failed`; publish to retry queue with backoff

### Deduplication
- Each notification has a `notification_id` (UUID or Snowflake)
- Workers check Redis for `sent:{notification_id}` before sending
- Set with TTL of 24h after send to prevent duplicates on retry

## Data Model

```
notifications:
  id             BIGINT PK
  user_id        BIGINT
  channel        ENUM(push, sms, email)
  template_id    VARCHAR
  payload        JSONB
  status         ENUM(pending, sent, failed, delivered)
  created_at     TIMESTAMP
  sent_at        TIMESTAMP
  retry_count    INT DEFAULT 0

user_preferences:
  user_id        BIGINT PK
  channel        ENUM
  opted_out      BOOL
  quiet_start    TIME    -- e.g., 22:00
  quiet_end      TIME    -- e.g., 08:00
  timezone       VARCHAR
```

## Template System

- Templates stored in DB with placeholders: `Hello {{name}}, your order {{order_id}} shipped.`
- Rendered at worker time using user-specific data from the event payload
- Support localization: one template row per language

## Retry Strategy

- **Exponential backoff**: retry at 1s, 5s, 30s, 5min, 30min
- **Dead-letter queue**: after 5 failures, move to DLQ; alert on-call
- **Per-provider circuit breaker**: stop sending to a provider that is consistently failing

## Scheduling (Marketing Blasts)

- Producer pre-calculates send time per user (respecting timezone + quiet hours)
- Jobs stored in a scheduled task table; a cron-like job enqueues at the right time
- Use priority lanes in Kafka: transactional > triggered > marketing

## API Design

```
POST /notifications/send
{
  "user_id": "u123",
  "template_id": "order_shipped",
  "channels": ["push", "email"],
  "data": { "order_id": "o456", "eta": "Apr 9" }
}
Response 202: { "notification_id": "n789" }

GET /notifications/{id}/status
Response 200: { "status": "delivered", "sent_at": "..." }
```

## Scaling & Trade-offs

- **Kafka partitioning**: partition push topic by `user_id % N` → ordered delivery per user
- **Token freshness**: push tokens expire; on APNs error code 410, delete stale token from DB
- **Email batching**: providers like SES accept batch sends; group emails in worker before API call
- **Analytics**: stream delivery events to data warehouse for open-rate / click-rate reporting

## Key Decisions & Why

| Decision | Reason |
|---|---|
| Kafka between API and workers | Decouples ingestion from delivery; handles burst |
| Dedup via Redis | Fast O(1) check before third-party API call |
| Per-channel Kafka topics | Independent scaling of push vs. SMS vs. email workers |
| Exponential backoff + DLQ | Handles transient provider failures without data loss |

## What Mid-to-Senior Candidates Often Miss

- Quiet hours / timezone handling (sending at 3am in user's local time)
- Stale push token cleanup (iOS revokes tokens; must handle APNs feedback service)
- Deduplication on retry (at-least-once + retry = potential duplicates without dedup)
- Priority lanes (marketing blast should not delay transactional notifications)
- Third-party provider circuit breaker (cascading failure if one provider goes down)