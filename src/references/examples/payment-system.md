# Payment System

## Problem Statement
Design a payment system (like Stripe or PayPal) that processes financial transactions between
payers and payees reliably, with exactly-once semantics, fraud detection, and auditability.

## Requirements

**Functional**
- Charge a payment method (card, bank account, wallet)
- Transfer funds between accounts
- Refund a previous charge
- Query transaction history
- Webhook delivery for payment events

**Non-Functional**
- Exactly-once processing: double-charging is catastrophic
- Consistency: strong — financial data must always be consistent
- Durability: no transaction loss ever
- Latency: payment initiation < 3s p99
- Auditability: full audit trail for every state transition
- Scale: 1M transactions/day

## High-Level Design

```
Client
  │  POST /charge
  ▼
Payment API
  ├─► Idempotency Layer (Redis / DB)
  ├─► Fraud Detection Service
  ├─► Payment Processor (Stripe/Adyen gateway)
  └─► Ledger Service → Double-Entry Ledger DB
                └─► Webhook Delivery Service
```

## Idempotency (Core to Payment Safety)

Every payment request must carry an `idempotency_key` (client-generated UUID).

```
function processPayment(idempotency_key, payload):
    # Check if already processed
    result = db.query("SELECT * FROM idempotency_keys WHERE key = ?", idempotency_key)
    if result:
        return result.cached_response   # return exact same response as before

    # Mark as in-progress (prevents concurrent duplicate)
    db.insert("INSERT INTO idempotency_keys (key, status) VALUES (?, 'processing')")

    # Process payment
    response = chargePaymentGateway(payload)

    # Persist result atomically with idempotency record
    db.transaction:
        update idempotency_keys set status='done', response=response WHERE key=idempotency_key
        insert into transactions (...)
    
    return response
```

- Idempotency record persisted in DB (not Redis alone — Redis restart loses state)
- TTL: 24 hours (beyond that, client should create a new payment intent)

## Double-Entry Ledger

All money movements use double-entry accounting — every transaction debits one account and credits another.

```
ledger_entries:
  entry_id       BIGINT PK
  transaction_id BIGINT
  account_id     BIGINT
  amount         DECIMAL(19,4)   -- always positive
  direction      ENUM(debit, credit)
  currency       CHAR(3)
  created_at     TIMESTAMP

-- Sum should always be zero across all entries for a transaction
-- SUM(credits) - SUM(debits) = 0

accounts:
  account_id     BIGINT PK
  owner_id       BIGINT
  account_type   ENUM(user, merchant, fee, escrow, external)
  currency       CHAR(3)
  balance        DECIMAL(19,4)  -- denormalized; recomputable from ledger
```

Example — $100 charge from user to merchant:
```
Debit  user_account   $100
Credit merchant_account $97
Credit fee_account     $3
```

## Transaction Flow

```
1. Client: POST /charges { amount: 100, currency: USD, source: card_id, idempotency_key: uuid }

2. Idempotency check (return if duplicate)

3. Fraud check:
   - Rule engine: amount > threshold, velocity check, card country mismatch
   - ML model: risk score; if high → 3DS challenge or decline

4. Create pending transaction record:
   INSERT transactions (status='pending', ...)

5. Call payment gateway (Stripe/Adyen):
   - On success: update status='success'; write ledger entries
   - On failure: update status='failed'; no ledger entries

6. Deliver webhook to merchant: { event: charge.succeeded, ... }
```

## Payment State Machine

```
           ┌──────────────────────────────────────┐
  pending ──► processing ──► succeeded ──► refunded
                  │
                  └──► failed
                  └──► disputed
```

All state transitions written to an audit log (append-only).

## Refund Flow

```
POST /refunds { charge_id: c123, amount: 50 }

1. Idempotency check
2. Verify charge exists and is in 'succeeded' state
3. Amount ≤ original charge amount
4. Call gateway refund API
5. Write reverse ledger entries:
   Debit  merchant_account $50
   Credit user_account     $50
6. Update charge status to 'partially_refunded' or 'refunded'
```

## Webhook Delivery

```
Kafka → Webhook Worker → Merchant endpoint

Retry policy: 1s, 5s, 30s, 5min, 30min, 2h, 8h (up to 72h)
Merchant must return 2xx to ack; otherwise retry
Signed with HMAC-SHA256 using merchant's webhook secret
Webhook log: store each attempt (status, response_code, attempt_at)
```

## Fraud Detection

- **Real-time rules** (synchronous, <50ms): velocity limits, blocked cards, amount thresholds
- **ML model** (synchronous for high-risk, async for low-risk): behavioral features (time of day, device fingerprint, merchant category)
- **3D Secure**: redirect user for bank authentication on high-risk transactions
- **Chargeback protection**: monitor dispute rate; flag merchants above 1%

## Reconciliation

Daily batch job:
- Compares our ledger with gateway settlement report
- Any discrepancy triggers alert and manual review
- Ensures external funds match internal accounting

## API Design

```
POST /charges
Headers: Idempotency-Key: <uuid>
Body: { "amount": 1000, "currency": "USD", "source": "card_id", "description": "Order #42" }
Response 200: { "charge_id": "c123", "status": "succeeded", "amount": 1000 }
Response 402: { "error": "card_declined", "decline_code": "insufficient_funds" }

POST /refunds
Headers: Idempotency-Key: <uuid>
Body: { "charge_id": "c123", "amount": 500, "reason": "requested_by_customer" }
Response 200: { "refund_id": "r45", "status": "succeeded" }

GET /charges/{id}
GET /charges?customer_id=u99&limit=20&starting_after=c120
```

## Scaling & Trade-offs

- **DB**: use PostgreSQL with serializable isolation for financial transactions; no eventual consistency
- **Currency handling**: store amounts as integers in smallest unit (cents); use DECIMAL(19,4) for display
- **Multi-currency**: convert at payment time using locked exchange rate; store both original and base currency amounts
- **Async processing**: for non-blocking UX, return `status=pending` immediately; update via webhook

## Key Decisions & Why

| Decision | Reason |
|---|---|
| Idempotency keys in DB | Prevents double-charge on network retries |
| Double-entry ledger | Always balanced; enables full audit and reconciliation |
| Serializable DB isolation | Financial correctness; no dirty reads or phantom inserts |
| HMAC-signed webhooks | Merchants can verify event authenticity |
| Amount in smallest unit (cents) | Avoids floating-point rounding errors |

## What Mid-to-Senior Candidates Often Miss

- Idempotency key storage (must be in durable DB, not Redis alone)
- Double-entry accounting (sum of all ledger entries must always be zero)
- Currency as integer (never float for money)
- Reconciliation with payment gateway (your records vs. their settlement)
- Refund as new ledger entries (not deletion of original entries)