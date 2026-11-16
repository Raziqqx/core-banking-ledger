# Design

DECISIONS.md is the full log. This page summarises the decisions that matter most.

## Key decisions and trade-offs

| Decision | Why | Alternative considered |
|---|---|---|
| Modular monolith with Maven modules that depend only downward | Running 3–4 JVMs on a 7 GB machine was not worth it. The boundaries are kept: payments has no foreign key to accounts, and notifications only consumes Kafka events | Separate deployables from day one |
| Spring JDBC (`JdbcClient`) instead of JPA | The ledger needs exact SQL: `FOR UPDATE` ordering, version-guarded updates, `ON CONFLICT`, `SKIP LOCKED` | JPA/Hibernate, where hidden flushes and dirty checking make this harder to reason about |
| `long` minor units, one currency per account | No floating point anywhere money is stored | `BigDecimal` columns |
| Signed postings plus a system funding account per currency | The sum over all accounts is always 0, so any created money shows up in the audit | Debit/credit columns, and deposits from nowhere |
| Cached `balance`/`held` updated in the same transaction, with `LedgerAuditor` to check the cache | O(1) balance reads, and drift is detectable | Summing postings on every read |
| Overdraft rule in code and as a DB `CHECK` | Defence in depth. A test bypasses the code to prove the constraint holds | Code only |
| Both locking modes behind one code path (`ledger.locking`) | Lets the two strategies be compared on the same workload | Picking one mode |
| Orchestrated saga with holds | Reserve → post → notify, with compensation in reverse. Holds make the reserve step reversible | Choreography over events, or a single DB transaction (which would not survive a split into services) |
| Transactional outbox + idempotent consumer | No dual write. Delivery is at least once, and the effect is exactly once (unique `event_id`) | Publishing straight from the request thread |
| Idempotency key claimed with `INSERT ... ON CONFLICT`, scoped `<sub>:<key>` | Atomic across threads and instances, and two clients cannot collide | Redis `SETNX`, or a check-then-insert |
| Recovery always compensates, never rolls forward | The conservative choice for money, and it cannot double-post | Resuming the saga from its last step |
| Per-instance rate limit (Bucket4j + Caffeine) | Simple, with no extra infrastructure | Bucket4j with a Postgres/Redis backend for a cluster-wide limit |
| GenAI fails closed to `OTHER` and never throws | A category is advisory and must never block money movement | Surfacing model errors to the client |

## Concurrency: what the stress test found

- **Optimistic mode deadlocked at first.** Transfers A→B and B→A took their UPDATE row locks in
  opposite orders, and Postgres reported `deadlock detected`. Sorting the deltas by account id
  fixed it. Optimistic locking still takes row locks at write time.
- **Optimistic retries could starve.** With security on, one run gave up after 50 attempts on a
  hot account. Linear backoff (1–11 ms) was replaced with full-jitter exponential backoff capped at
  64 ms.

## Baseline vs improved

Every number below is from `results/concurrency_it.csv` (16 threads, 4,000 ops, 10 accounts). The
machine was an i5-14500HX with 7 GB RAM under WSL2, in the same `scripts/reproduce.sh` run as the k6
test (2026-11-15).

| scenario | ops/s | optimistic retries |
|---|---|---|
| transfers, OPTIMISTIC (baseline) | 565 | 6,562 |
| transfers, PESSIMISTIC (default) | 673 | 0 |
| saga payments, PESSIMISTIC (hold + capture + outbox) | 426 | 0 |

On this hot-account workload, pessimistic locking gives 19% more throughput than optimistic and
needs no retries, so it is the default. The saga does more work per payment than a raw transfer,
which explains its lower rate.

DECISIONS.md also records that the backoff change cut optimistic retries per 4,000 ops from about
9,700 to about 6,300. That earlier run is not kept in `results/`. The committed run shows 6,562.

The end-to-end HTTP result is in `results/k6_payments.md`: 380.4 req/s, with p50/p95/p99 of
50.2/70.1/85.9 ms and 0 errors over 23,477 requests. That was 20 VUs on a single instance with
100% trace sampling. The ledger audit afterwards was clean.

## Saga correctness: review fixes (commit 5a272be)

1. **Compensation was registered per step, after the step was recorded.** If `store.progress`
   failed after `captureHold` had moved the money, the saga had nothing to undo and still reported
   `COMPENSATED`. Now each step is split into `effect` (the ledger call) and `record`. The step is
   pushed onto the compensation stack as soon as `effect` returns. This is covered by
   `PaymentSagaTest.progressFailureAfter*` and `SagaRecoveryIT.progressFailureAfter*`.
2. **A crash mid-saga left the payment in flight forever**, with an active hold or a captured
   entry and an idempotency key stuck `IN_PROGRESS` that returned 409 to every retry. Now
   `SagaRecovery` claims stale payments with `SKIP LOCKED` and compensates them idempotently. V5
   adds `holds.reference` and `holds.entry_id`, so recovery can find what a dead saga did. The job
   also completes or deletes stale keys. A slow saga and the recovery job cannot both win, because
   hold transitions and `PaymentStore.progress`/`finish` are conditional on the current state.
   `SagaRecoveryIT` covers four crash points and four concurrent recovery runs over 10 stale payments.

## Known limitations

- It is a **modular monolith**: one deployable, with service boundaries kept but not enforced by the network.
- **Recovery always compensates.** A payment that crashed after posting is reversed, not completed.
- **No statement Q&A.** Only the categoriser half of the GenAI feature was built.
- **The categoriser is tested with the mock provider only.** The Anthropic provider is unit-tested
  with a mocked SDK, and no integration test calls the endpoint with the flag on.
- **The image scan is non-blocking.** Trivy runs with `exit-code: 0` until the baseline is triaged.
- Idempotency keys have no TTL. A reversal fails with `COMPENSATION_FAILED` if the payee has
  already spent the funds. Traces break at the outbox. The rate limit applies per instance. FX
  transfers are rejected. There are no consumer-side or messaging contracts.
- The service has never been deployed to a real cluster. The Helm chart is only linted and
  schema-checked.

## Next steps

1. Split `notifications` into its own deployable, and add messaging contracts for `payment-events`.
2. Store `traceparent` in the outbox row so a trace follows the event end to end.
3. Add a TTL job for idempotency keys, and alerting on `bank.saga.recovered{outcome="COMPENSATION_FAILED"}`.
4. Make Trivy blocking after triage, and add a deploy job once a cluster exists.
5. Add statement Q&A, and an integration test with the categoriser flag on.
6. Move to a cluster-wide rate limit, and add FX with explicit rate snapshots.
