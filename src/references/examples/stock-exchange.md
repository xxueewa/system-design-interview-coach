# Stock Exchange

## Problem Statement
Design a stock exchange system that matches buy and sell orders in real-time, maintains a fair
and transparent order book, and disseminates market data to participants with ultra-low latency.

## Requirements

**Functional**
- Submit limit and market orders (buy/sell)
- Cancel or modify existing orders
- Match orders: price-time priority (best price first; same price → first submitted first matched)
- Order book: display best bid/ask prices and quantities
- Trade execution: confirm fills to both parties
- Market data feed: broadcast order book updates and trade ticks

**Non-Functional**
- Latency: order-to-ack < 1ms; matching engine single-threaded for determinism
- Throughput: 1M orders/sec per symbol during peak
- Ordering: total ordering of events per symbol (no two events can be concurrent)
- Fairness: no order skips the queue based on identity
- Auditability: every event persisted before action taken

## High-Level Design

```
Client / Broker
    │  order submission (TCP/FIX protocol)
    ▼
Order Gateway
    │  validated order
    ▼
Sequencer (assigns global sequence number)
    │
    ▼
Matching Engine (single-threaded per symbol)
    │
    ├─► Order Book (in-memory)
    │       ├─ Bid side (max-heap)
    │       └─ Ask side (min-heap)
    │
    ├─► Trade execution → Clearing & Settlement
    └─► Market Data Publisher → Clients (multicast UDP)
```

## Sequencer

- All orders must be totally ordered before entering the matching engine
- Sequencer assigns a monotonically increasing sequence number (like a WAL entry)
- Written to a durable log (Kafka or custom WAL) before processing
- If sequencer crashes, replay from log restores exact state

```
Sequenced event log (append-only):
  seq_no | timestamp_ns | symbol | event_type | order details
  1      | 1712000000001 | AAPL  | NEW_ORDER  | { side: buy, qty: 100, price: 175.00 }
  2      | 1712000000002 | AAPL  | NEW_ORDER  | { side: sell, qty: 50, price: 175.00 }
  3      | 1712000000003 | AAPL  | TRADE      | { buy_order: 1, sell_order: 2, qty: 50, price: 175.00 }
```

## Matching Engine

Single-threaded per symbol — guarantees determinism, no locking.

### Order Book Structure

```
Order Book (per symbol):
  Bids (buy orders): sorted by price DESC, then by time ASC
  Asks (sell orders): sorted by price ASC, then by time ASC

Internal data structure:
  Price level map: price → doubly linked list of orders at that price
  Order map: order_id → order node (for O(1) cancel)
```

### Matching Algorithm (Price-Time Priority)

```
function onNewOrder(order):
    if order.type == MARKET:
        order.price = INFINITY (buy) or 0 (sell)
    
    while order.remaining_qty > 0:
        best_opposite = order.side == BUY ? asks.peek() : bids.peek()
        
        if best_opposite == null: break  # no match
        if order.side == BUY and order.price < best_opposite.price: break
        if order.side == SELL and order.price > best_opposite.price: break
        
        trade_qty = min(order.remaining_qty, best_opposite.remaining_qty)
        trade_price = best_opposite.price  # passive side sets price
        
        executeTrade(order, best_opposite, trade_qty, trade_price)
        
        if best_opposite.remaining_qty == 0:
            remove best_opposite from book
    
    if order.remaining_qty > 0 and order.type == LIMIT:
        add order to book (resting order)
```

## Order Types

| Type | Behavior |
|---|---|
| Limit | Rest in book if not immediately matchable |
| Market | Match immediately at best available price; remaining cancelled |
| IOC (Immediate or Cancel) | Match what's possible immediately; cancel remainder |
| FOK (Fill or Kill) | Execute completely or cancel entirely |
| Stop | Activated when market price hits stop price; becomes market/limit |

## Orders Data Model

```
orders:
  order_id      BIGINT PK (Snowflake)
  client_id     BIGINT
  symbol        VARCHAR(10)
  side          ENUM(buy, sell)
  order_type    ENUM(limit, market, ioc, fok)
  price         DECIMAL(19,4)  -- null for market orders
  qty           INT
  remaining_qty INT
  status        ENUM(open, partial, filled, cancelled)
  created_at    TIMESTAMP
  seq_no        BIGINT   -- sequencer-assigned; deterministic ordering

trades:
  trade_id      BIGINT PK
  symbol        VARCHAR(10)
  buy_order_id  BIGINT
  sell_order_id BIGINT
  price         DECIMAL(19,4)
  qty           INT
  executed_at   TIMESTAMP
```

## Clearing and Settlement

After a trade executes:
1. Clearing: verify both parties have funds/securities (risk check)
2. Settlement: transfer securities from seller to buyer; cash from buyer to seller (T+2 for equities)
3. Central Counterparty (CCP): interposes itself between buyer and seller to reduce counterparty risk

## Market Data Feed

- Best bid/offer (BBO) and full order book depth published after each event
- Multicast UDP to all subscribers simultaneously (exchange uses FPGA NICs for nanosecond precision)
- Clients implement sequence gap detection; request retransmission on gap
- Consolidated feed: all trades + quotes; direct feed: raw order book events

## Risk Controls (Pre-Trade)

- Order gateway enforces: max order size, price bands (circuit breakers), account buying power
- If stock price moves >5% in 5 minutes → trading halt; notify all participants
- Fat-finger protection: reject orders priced >10% from last trade price

## API Design (FIX Protocol simplified)

```
# New Order
→ { MsgType: D, Symbol: AAPL, Side: 1(buy), OrderQty: 100, Price: 175.00, OrdType: 2(limit) }
← { MsgType: 8, ExecType: 0(new), OrdStatus: 0(new), OrderID: o123 }

# Cancel Order
→ { MsgType: F, OrderID: o123 }
← { MsgType: 8, ExecType: 4(cancelled), OrdStatus: 4 }

# Trade Execution Report (unsolicited)
← { MsgType: 8, ExecType: 2(trade), LastPx: 175.00, LastQty: 50, CumQty: 50 }

# Market Data (Level 2)
← { Symbol: AAPL, Bids: [{175.00, 500}, {174.99, 200}], Asks: [{175.01, 300}] }
```

## Scaling & Trade-offs

- **One matching engine per symbol**: complete isolation; no cross-symbol coordination needed
- **Sequencer as bottleneck**: mitigated by batching; LMAX Disruptor pattern (ring buffer without locks)
- **In-memory order book**: matches in microseconds; persisted via event log for recovery
- **Co-location**: market makers run servers in exchange data centers to minimize network latency

## Key Decisions & Why

| Decision | Reason |
|---|---|
| Single-threaded matching engine | Determinism without locking; simpler reasoning about fairness |
| Sequencer + event log | Total order of events; crash recovery by replay |
| In-memory order book | Microsecond matching; log provides durability |
| Price-time priority | Industry standard; clear fairness rules |
| Multicast UDP for market data | One send to all subscribers; nanosecond-level broadcast |

## What Mid-to-Senior Candidates Often Miss

- Sequencer for total ordering (without it, concurrent orders create non-deterministic matches)
- Price-time priority details (same price → earliest order wins; must track insertion time)
- Event log for crash recovery (in-memory state + log = no data loss)
- Circuit breakers / trading halts (price bands protect against runaway algorithms)
- Clearing and settlement as a separate step post-trade (trade != money movement)