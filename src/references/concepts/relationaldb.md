## Relational Database

### ACID
1. Atomic: "All or Nonthing". Either all operations within the transaction succeed, or none of them are applied.
2. Consistency: A transaction must transform the db from one valid state to another.
3. Isolation: Concurrent, simultaneous transactions should not interfere with each other.
4. Durability: Once a transaction has been committed, its changes are permanent and will survive system failures.

### Isolation Level

| Level                               | Resolved Problems                                           |
|-------------------------------------|-------------------------------------------------------------|
| Read Uncommited                     | NA                                                          |
| Read commited (MVCC)                | Dirty Reads                                                 |
| Repeated Readable (MVCC + Gap lock) | Dirty Reads, Non-Repeatable Reads, Phantom Reads (Gap lock) |
| Serializable (Table lock, Row lock) | Dirty Reads, Non-Repeatable Reads, Phantom Reads            |

- Standard ANSI SQL definition of Repeatable Read → does NOT prevent phantom reads (gap lock not part of the spec)
- MySQL InnoDB implementation of Repeatable Read → gap locks DO prevent phantom reads for locking reads (SELECT FOR UPDATE)
- Dirty Reads means read the uncommited changes
- Non-Repeatable Reads means within one transaction, the read of the same record are different
- Phantom Reads means within on transaction, the number of records changed after another transaction commiting the changes. 

### Gap Lock && Next-Key Lock
- ONLY in Repeated Readable level
 Gap lock has open boundaries at both start row and end row, Next-key lock has close boundary on start row. 

#### Example
  | ID | Value |
  |----|-------|
  | 1  | NA    |
  | 5  | NA    |
  | 7  | NA    |

- Gap Lock ()
- Next-Key Lock (]

SELECT * from table WHERE id=5 for UPDATE
  -- Transaction A holds a gap lock on (4, 6) — id 5 doesn't exist
  -- Transaction B tries:                                                                                                                            
  INSERT INTO users (id) VALUES (5);  -- BLOCKED (gap lock prevents this)
  UPDATE users SET name='x' WHERE id = 5;  -- NOT blocked (no row to update)

SELECT * from table WHERE id BETWEEN 4 AND 6 for UPDATE
  -- Transaction A holds a next-key lock on (4, 6] — id 5 exists                                                                                     
  -- Transaction B tries:                                                                                                                            
  INSERT INTO users (id) VALUES (5);  -- BLOCKED
  UPDATE users SET name='x' WHERE id = 5;  -- BLOCKED (record lock)

### Snapshot Read && Current Read
#### Snapshot Read
- When the transaction first read the data, there will be a 'Consistent Read View' being generated, and it decides the version which the transaction could read.
- The consistent read view consists of active transaction id list, maximum transaction id, current transaction id.
- A plain SELECT (no FOR UPDATE, no LOCK IN SHARE MODE) in Repeatable Read isolation level is a snapshot read.
#### Current Read 
- UPDATE, DELETE, SELECT ... FOR UPDATE
- InnoDB will read the latest commited data and add the lock to prevent the changes from other transactions. 

### Implementation of Repeatable Read
- Definition: in the same transaction, read the same record multiple times result in the same result even if other transaction changes the record
- MVCC + Gap Lock