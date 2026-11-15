# DECISIONS — core-banking-ledger

Decisions made while building, with the reason for each. Newest milestone last.

## Stack and layout (M1)
- **Spring Boot 3.5.6, Java 21, Maven multi-module**: `common`, `accounts`, `ledger`, `payments`,
  `notifications` are library modules; `app` is the bootable service that wires them, owns the Flyway
  migrations and holds the Testcontainers integration tests. Pinned to 3.5.6 as a mature 3.x patch release.
- **Modular monolith for now, with service boundaries kept**. Modules depend only downward
  (payments → ledger → accounts → common). Payments has no foreign key to accounts, and notifications
  consumes only Kafka events, so either can be split into its own deployable later without schema
  changes. Running 3–4 JVMs on a 7 GB machine was not worth it for M1/M2. Run B/C may split
  `notifications` out for the contract tests.
- **Spring JDBC (`JdbcClient`), not JPA**. Ledger code needs exact control over SQL: `FOR UPDATE`
  ordering, `version`-guarded updates, `ON CONFLICT`, `SKIP LOCKED`. Hidden flushes and dirty checking
  make that harder to reason about.
- **Testcontainers 1.21.4** overrides Boot's managed version. Docker Engine 29 rejects API versions
  below 1.44, which older docker-java clients send by default. No `DOCKER_HOST` or Ryuk overrides were needed.
- Images: `postgres:16-alpine` and `redpanda:v24.2.18` (dev-container mode, 1 core, 512 MB) are kept
  small because the disk is nearly full.

## Ledger model (M1)
- **Amounts are `long` minor units**, and each account has one currency (ISO code). No floating point.
  BigDecimal is only used to format notification text.
- **Signed postings**: each posting is a signed delta to one account, and a journal entry must sum to zero
  **per currency** (`EntryValidator`). The balance is the sum of the account's postings.
- **System funding account per currency** (lazily created, unique partial index). Deposits are
  entries from it, so the sum over *all* accounts is always 0 and money only appears through a
  recorded entry. It is the only account allowed to go negative.
- **Cached balances**: `accounts.balance`/`held` are a cache updated in the same transaction as the
  postings. `LedgerAuditor` (`GET /ledger/audit`) recomputes from postings/holds and reports any entry
  that is out of balance, any balance mismatch and any held-amount mismatch.
- **Overdraft rule**: a change that lowers `available = balance - held` must keep it ≥ `-overdraft_limit`
  (default 0). Credits are always allowed. The rule is in `Account.apply` and is also a DB `CHECK`
  constraint (defence in depth; covered by a test that bypasses the code).
- **Holds** (for the saga's reserve step): money stays in `balance` but moves to `held`. Hold status
  changes use `UPDATE ... WHERE status='ACTIVE'`, so capture and release cannot both win.
  Release is a no-op on a non-active hold, so compensation is idempotent.
- **Reversal** posts the mirror entry, with `reverses` set to the original. A unique index allows at most one
  reversal per entry.
- **Cross-currency transfers are rejected** (400). FX is out of scope for now.

## Concurrency (M1)
- Two strategies, selected with `ledger.locking`, behind the same code path:
  - `PESSIMISTIC` (default): `SELECT ... WHERE id = ANY(?) ORDER BY id FOR UPDATE`.
  - `OPTIMISTIC`: plain read, then `UPDATE ... WHERE version = ?`. On 0 rows, the whole transaction
    is re-run (up to 50 attempts, small random backoff). Retries are counted.
- **Writes are made in ascending account-id order in both modes.** The first concurrency run found that
  OPTIMISTIC mode deadlocked (Postgres `deadlock detected`). Two transfers A→B and B→A took the row
  locks of their UPDATEs in opposite orders. Sorting deltas by id (TreeMap) fixed it. This is a real
  finding to cite: optimistic locking still takes row locks at write time.
- Each `LedgerService` method runs in its own transaction (TransactionTemplate), which keeps it atomic
  on its own, as a remote call would be. The saga composes these calls.

## Payments (M2)
- **Orchestrated saga** `reserve (hold) → post (capture hold into an entry) → notify (COMPLETED + outbox
  event in one transaction)`. On failure, the completed steps are compensated in reverse order (reverse
  the entry, release the hold). Outcomes: `COMPLETED`, `FAILED` (nothing to undo), `COMPENSATED`, or
  `COMPENSATION_FAILED` (flagged for an operator, with no event emitted).
- **Idempotency keys**: the key is claimed with `INSERT ... ON CONFLICT DO NOTHING` (atomic across
  threads and instances), together with the SHA-256 of the request JSON. The outcomes are:
  - same key and same body after completion: the stored status code and body are returned byte for byte;
  - different body: 409;
  - same key while the first request is still running: 409 (the client retries later).
  
  Failed payments (422) are stored and replayed too.
- **Transactional outbox** (`outbox` table, `published_at`). `OutboxRelay` polls every 200 ms. It locks a batch with
  `FOR UPDATE SKIP LOCKED` (safe with several instances), sends to Redpanda keyed by payment id
  (per-payment ordering), waits for acks (`acks=all`, idempotent producer), then marks the batch published
  in the same transaction. Delivery is **at least once**.
- **Notifications** are an idempotent consumer: `notifications.event_id` is unique and inserts use
  `ON CONFLICT DO NOTHING`. Each event therefore gives exactly one notification, even when redelivered (tested).

## Known gaps (to address in later runs)
- **Saga crash recovery**: if the process dies mid-saga, the payment stays `RESERVED`/`POSTED` and its
  idempotency key stays `IN_PROGRESS`, so retries get 409. The fix is a recovery job that resumes or compensates stale
  sagas using `payments.hold_id`/`entry_id` (the state is already persisted for this).
- Idempotency keys are global, not scoped per client, until auth arrives in Run B. Keys never expire (they need a TTL job).
- A reversal can fail if the payee has already spent the funds (the overdraft rule applies). The saga then reports
  `COMPENSATION_FAILED`. Real banks handle this with a receivable or manual process.
- Postgres and the broker are not covered by one transaction. That is the point of the outbox, but the relay still adds
  up to one polling interval of latency.
