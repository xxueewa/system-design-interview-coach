# Digital Wallet

## Problem Statement
Design a digital wallet system (like PayPal, Venmo, or Cash App) where users can store funds,
transfer money to other users, link bank accounts, and cash out — with exactly-once transfers
and strong consistency.

## Requirements

**Functional**
- Add funds from a linked bank account or card
- Send money to another user (peer-to-peer transfer)
- Request money from another user
- Cash out to a bank account
- View transaction history and balance
- Split bills (group payment requests)

**Non-Functional**
- Consistency: balance must always be accurate; no double-spend
- Exactly-once: transfer processed exactly once even if retried
- Durability: no money lost ever
- Latency: balance read < 100ms; transfer < 2s
- Scale: 100M users; 10M transfers/day

## High-Level Design

```
Client
  ├─► Wallet API
  │     ├─► Balance Service (Redis read cache + DB)
  │     ├─► Transfer Service → Ledger DB (MySQL, serializable)
  │     └─► External Payment Adapter (bank ACH, card network)
  └─► Notification Service (webhooks, push notifications)
```

## Account and Balance Model

Use a **double-entry ledger** — every transfer debits one account and credits another.
Balance is derived from ledger entries (never stored as a mutable field to avoid inconsistency).

```
wallets:
  wallet_id    BIGINT PK
  user_id      BIGINT UNIQUE
  currency     CHAR(3) DEFAULT 'USD'
  status       ENUM(active, frozen, closed)
  created_at   TIMESTAMP

ledger_entries:
  entry_id       BIGINT PK (Snowflake)
  transaction_id BIGINT       -- groups debit + credit
  wallet_id      BIGINT
  amount         DECIMAL(19,4) -- always positive
  direction      ENUM(debit, credit)
  entry_type     ENUM(transfer, top_up, cashout, fee, refund)
  created_at     TIMESTAMP
  idempotency_key VARCHAR(64) UNIQUE

-- Balance derived:
SELECT SUM(CASE WHEN direction='credit' THEN amount ELSE -amount END)
FROM ledger_entries WHERE wallet_id = ?
```

Cached balance in Redis for fast reads; invalidated on each write.

## Transfer Flow (P2P)

```
POST /transfers { from_wallet, to_wallet, amount, idempotency_key }

1. Idempotency check:
   SELECT * FROM idempotency_log WHERE key = ? → if exists, return cached result

2. BEGIN SERIALIZABLE TRANSACTION
   a. Lock sender wallet row: SELECT ... FOR UPDATE
   b. Compute sender balance from ledger
   c. Validate: balance >= amount, wallets active, not self-transfer
   d. Insert ledger entry: debit sender
   e. Insert ledger entry: credit recipient
   f. Record idempotency_log entry with response
   COMMIT

3. Invalidate Redis balance cache for both wallets
4. Enqueue notification events (Kafka)
5. Return { transfer_id, status: 'completed' }
```

### Why not update a `balance` column directly?
- Concurrent updates cause race conditions even with locks in some isolation levels
- Ledger is append-only → immutable audit trail
- Balance is always re-derivable → no inconsistency possible

## Idempotency

- Client sends a UUID `idempotency_key` per transfer request
- Key stored in `idempotency_log` with the full response
- On retry: return the same response as original → client can safely retry on network failure

```
idempotency_log:
  key          VARCHAR(64) PK
  response     JSON
  created_at   TIMESTAMP
  expires_at   TIMESTAMP   -- cleanup after 24h
```

## Balance Read (Optimized)

Computing balance from ledger is accurate but slow for large histories.
Use **balance cache**:

```
Redis key: balance:{wallet_id}
Value: { balance: 150.75, as_of_entry_id: 10042 }

On read:
  cached = Redis.get(balance:{wallet_id})
  if cached:
    # Apply any ledger entries after as_of_entry_id
    delta = SUM(entries WHERE entry_id > cached.as_of_entry_id AND wallet_id = ?)
    return cached.balance + delta
  else:
    # Full recompute from ledger
    balance = SUM(ledger_entries)
    Redis.set(balance:{wallet_id}, {balance, as_of_entry_id: latest_entry_id})
    return balance
```

## Funds In / Funds Out

### Top-up (Add Funds)
```
1. User links bank account (Plaid) or card
2. POST /topup { source: bank_account_id, amount: 100 }
3. Charge external source via payment gateway (async ACH 2-3 days, or instant card)
4. On gateway success webhook → credit wallet ledger entry
5. Handle chargebacks: freeze wallet if dispute filed
```

### Cash Out
```
1. POST /cashout { destination: bank_account_id, amount: 50 }
2. Debit wallet (immediate) → create pending external transfer
3. Initiate ACH transfer to bank (T+1 or T+2)
4. On bank confirmation → mark as settled
5. On failure → refund ledger credit back to wallet
```

## Request Money Flow

```
1. User A requests $20 from User B
2. Create money_requests record (status=pending)
3. Notify User B (push + email)
4. User B approves → trigger transfer (same as P2P transfer above)
5. Or User B declines → update status=declined
```

## Fraud and Limits

- Daily transfer limit per user (e.g., $10K unless verified)
- Velocity checks: >10 transfers/hour → flag for review
- KYC (Know Your Customer): identity verification required for limits above threshold
- Freeze wallet on suspicious activity; require manual review to unfreeze

## API Design

```
GET /wallet/balance
Response 200: { "balance": 150.75, "currency": "USD", "wallet_id": "w123" }

POST /transfers
Headers: Idempotency-Key: <uuid>
Body: { "to_wallet": "w456", "amount": 25.00, "note": "Dinner split" }
Response 200: { "transfer_id": "t789", "status": "completed", "new_balance": 125.75 }

POST /requests
Body: { "from_wallet": "w456", "amount": 20.00, "note": "Drinks" }
Response 201: { "request_id": "r99", "status": "pending" }

GET /transactions?limit=20&before=t700
Response 200: { "transactions": [{ "id": "t789", "type": "transfer", "amount": -25.00, "at": "..." }] }
```

## Scaling & Trade-offs

- **DB sharding**: shard by `wallet_id`; transfers between shards need distributed transactions (2PC or saga)
- **Cross-shard transfers**: write a transfer saga — debit source, enqueue message, credit destination; compensate on failure
- **High-read balance**: Redis cache with delta application keeps reads fast without DB scan
- **Multi-currency**: store amounts in the wallet's base currency; conversion at entry time with locked rate

## Key Decisions & Why

| Decision | Reason |
|---|---|
| Double-entry ledger | Append-only; always balanced; full audit trail |
| Serializable isolation | Prevents concurrent overdraft |
| Idempotency log in DB | Safe retries; prevents double transfers |
| Redis balance cache with delta | Fast reads without full ledger scan |
| Saga for cross-shard transfers | Avoids distributed 2PC (high latency, locking) |

## What Mid-to-Senior Candidates Often Miss

- Double-entry ledger (storing balance as a mutable column leads to race conditions)
- Idempotency key durability (must be in DB, not in-memory cache)
- Cross-shard transfer consistency (P2P between two users may be on different DB shards)
- Chargeback handling for top-ups (bank disputes require freeze + reconciliation)
- Balance cache invalidation (stale cache = incorrect balance displayed to user)