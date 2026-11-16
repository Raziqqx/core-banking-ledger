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

## Security, API docs and rate limiting (M3)
- **JWT resource server, no auth server in the repo**. Tokens are RS256 JWTs with `sub` (the customer id,
  equal to `accounts.owner`) and a `roles` claim (`ADMIN`/`CUSTOMER`) mapped to `ROLE_*`. Under the `dev`
  profile `DevTokenIssuer` generates an RSA key pair in memory at startup and serves `POST /dev/token`;
  no key material is committed and tokens die with the process. The decoder checks signature, `exp`/`nbf`
  and `iss`. Any other profile must set `spring.security.oauth2.resourceserver.jwt.issuer-uri`; without
  it there is no `JwtDecoder` and the app does not start, so it can never run open by accident.
- **Two layers of authorisation**. Coarse role rules sit in `SecurityConfig` (admin-only: open accounts,
  deposits, raw `/transfers`, `/ledger/audit`, `/notifications`; everything else needs ADMIN or CUSTOMER).
  Ownership needs the account row, so it is `AccountAccess` in the accounts module, called by the
  controllers: a customer may read only their own accounts, pay only *from* their own account, and read a
  payment only if they own its payer or payee account. System accounts belong to no customer.
- **Domain modules stay security-free**: they see a `CurrentCaller` interface (in `common`); the app
  implements it from the `SecurityContext`. Unit tests use a two-line fake.
- **Notifications are admin-only for now**: the notifications module owns no account data and must not
  depend on `accounts` (it is the most likely module to be split out). A customer view would join via the
  API gateway or carry the owner in the event.
- **403 vs 404**: a customer asking for a missing account gets 404 and for someone else's 403, which
  reveals that the id exists. Account ids are sequential so this leaks little; hiding it (404 for both)
  is a one-line change if required.
- **Idempotency keys are scoped per caller**: stored as `<sub>:<key>` (V4 widens the column to 301; `sub`
  is capped at 200 chars). This closes the M2 gap where two clients choosing the same key collided.
- **Rate limit**: Bucket4j token bucket per JWT subject on `POST /payments`, default 30 per minute with
  greedy refill (`ratelimit.payments.*`). It is an MVC interceptor, so it runs after authentication (an
  anonymous caller cannot drain someone else's bucket) and before the idempotency check (retries count).
  Over the limit: 429 problem+json with `Retry-After` in whole seconds, rounded up. Buckets live in a
  bounded Caffeine cache (100k clients, evicted after 5 idle periods, by when they are full anyway), so
  the limit is **per instance**; a cluster-wide limit would use Bucket4j's Postgres/Redis backend.
- **springdoc** 2.8.13 at `/v3/api-docs` and `/swagger-ui.html`, both public, with a bearer scheme so
  "Authorize" in Swagger UI works with a `/dev/token` token.
- **Tests use real tokens**, not `spring-security-test`'s `jwt()` shortcut, so the decoder, validators
  and role mapping are exercised. ITs run under `dev`; `IntegrationTestBase.call` adds an admin token unless
  the request sets its own, and lifts the rate limit. `RateLimitIT` gets its own context (3 per hour).
- **Finding: optimistic retries could starve**. With security on (more CPU load), one `ConcurrencyIT`
  OPTIMISTIC run threw after 50 failed attempts on a hot account. The backoff was linear (1-11 ms) and
  retrying writers kept colliding. Switched to full-jitter exponential backoff capped at 64 ms; retries
  per 4000 ops fell from ~9,700 to ~6,300 (results/concurrency_it.csv) and the test passes.

## Contracts, error handling and stress testing (M4)
- **Spring Cloud Contract, producer side** (2025.0.0 BOM, contract 4.3.0). Three YAML contracts in
  `app/src/test/resources/contracts/payments`: create payment (201, completed), invalid amount
  (400 problem+json with a field error) and get payment (200). The Maven plugin generates
  `contract.PaymentsTest` and the stubs jar. The tests are unit-phase (no Docker): `PaymentsContractBase`
  runs the real controller, bean validation, `ApiErrorHandler` and Jackson in standalone MockMvc and
  mocks everything behind the controller. Contracts pin the wire shape consumers depend on; auth and
  money movement are already covered by the Testcontainers ITs, so repeating them here would only
  slow the build. Contracts require `Authorization: Bearer .+` so a consumer stub forgetting the
  header will not match.
- **No consumer in the repo yet**: notifications consumes Kafka events, not this REST API. A consumer
  would use the generated stubs jar with Stub Runner. Messaging contracts for `payment-events` are the
  natural next step if notifications becomes its own deployable.
- **RFC 7807 everywhere**: every error is `application/problem+json` with `type`, `title`, `status`,
  `detail`, `instance` and a machine `code`. Framework errors (bad JSON, validation, missing header,
  404/405/415) get the `code` added in `handleExceptionInternal`, and body validation also lists
  `errors[{field, message}]`. Security-filter 401/403 use the same shape (`ProblemSecurityHandlers`).
  Unknown exceptions become a generic 500 with the cause logged and never returned. Covered by
  `ProblemDetailsIT` and the invalid-amount contract.
- **Exception kept on purpose**: a payment whose saga failed returns 422 with the *payment* as the body,
  not a problem. The client needs its id and final status, and the stored idempotent replay must be
  byte-identical.
- **Testcontainers ITs with security on**: all of them pass (they authenticate with dev-issuer admin
  tokens through `IntegrationTestBase`). `ConcurrencyIT` drives the services directly, under the web layer.
- **Stress test: the run A `ConcurrencyIT` counts**. It runs 16 threads x 4000 random transfers/saga
  payments over 10 accounts in both locking modes and checks conservation, no negatives and a clean
  audit. Adding a larger one would only add minutes to the build. The k6 HTTP load test in run C will
  stress the full stack (auth, rate limit, saga) instead.

## Observability and load testing (M5)
- **Test runs no longer touch tracked files**: `ConcurrencyIT` appends to `app/target/concurrency_it.csv`
  (override with `-Dconcurrency.results=...`); `scripts/reproduce.sh` copies it to `results/`. Before
  this every `mvn verify` dirtied the tree.
- **Custom metrics** (Micrometer, scraped at `/actuator/prometheus`):
  - `bank_payments_total{outcome}`: finished sagas by final status (COMPLETED, FAILED, COMPENSATED,
    COMPENSATION_FAILED, or ERROR when an unexpected exception escapes). The tag is the status the
    saga *decided*, kept in the saga context, not re-read from the store.
  - `bank_saga_duration_seconds{outcome}`: a timer with a percentile histogram, so p95/p99 can be
    aggregated across instances in PromQL (client-side percentiles cannot be).
  - `bank_outbox_lag_seconds` (age of the oldest unpublished row) and `bank_outbox_pending`: gauges
    that query Postgres at scrape time. Reading the table rather than relay state keeps them right with
    several relays, or with the relay dead, which is exactly when the lag matters. The query uses the
    partial `outbox_unpublished` index. `bank_outbox_publish_delay_seconds` times insert to broker ack.
- **Actuator exposure**: only `health`, `info` and `prometheus`, all unauthenticated so probes and the
  scrape work without tokens. Metrics reveal volumes but no customer data; in a cluster the endpoint
  is reachable only inside the network (a separate management port or a NetworkPolicy is the next step).
- **Tracing**: Micrometer Tracing with the OTel bridge, exported over OTLP/HTTP to Jaeger's collector
  (`:4318`). HTTP server spans and Kafka send/receive spans (template and listener
  observations) are recorded. Off by default (`TRACING_ENABLED`), so tests and plain runs never try to
  reach a collector. **Gap**: the trace does not follow an event *through the outbox*: the relay sends
  from a scheduler thread, so the Kafka span starts a new trace. Storing `traceparent` in the outbox
  row and restoring it on send would join them.
- **Structured logs**: Spring Boot's built-in ECS JSON format with `LOG_FORMAT=ecs` (the container
  image and Helm chart set it); the trace and span ids come from the MDC. Human-readable by default.
- **Observability stack** in compose under the `observability` profile: Prometheus 2.55 (5 s scrape),
  Grafana 11.3 with a provisioned datasource and one dashboard
  (`observability/grafana/dashboards/core-banking-ledger.json`: payments by outcome, saga and HTTP
  p50/p95/p99, outbox lag and publish delay, heap, Hikari), and Jaeger all-in-one 1.62 with an
  in-memory store capped at 20k traces. The app runs on the host; containers reach it through
  `host.docker.internal` (host-gateway). `--network host` did not work on this Docker setup.
- **k6 load test** (`scripts/k6/payments.js`, run by `scripts/run-k6.sh` in `grafana/k6:0.54.0`):
  20 constant VUs for 60 s. Each VU is a separate customer with its own JWT, paying from its own
  well-funded account (so failures are errors, not insufficient funds), and 5% of requests are
  idempotent replays. A closed model measures the throughput the single instance sustains. The rate
  limit is lifted for the run because the test measures the payment path; `RateLimitIT` covers the
  limiter. Tracing is on at 100% sampling, so the numbers are conservative. The script queries
  Prometheus mid-run (`results/k6_prometheus_check.json`) and runs `GET /ledger/audit` after
  (`results/k6_ledger_audit.json`).
- **Result** (results/k6_payments.md, i5-14500HX, WSL2, 7 GB; the committed run is from
  `scripts/reproduce.sh`): 380 req/s, p50 50 ms, p95 70 ms, p99 86 ms, 0 errors over 23.5k
  payments, and a clean ledger audit afterwards. Two earlier runs on the same machine, with other
  work running alongside, gave about 300 req/s with p99 114-123 ms, so expect roughly 25% run-to-run
  variance on a laptop. During the runs Prometheus showed `bank_payments_total{outcome="COMPLETED"}`
  equal to the saga timer count, an outbox lag of tens of ms and a publish-delay p99 of about 240 ms
  (the relay polls every 200 ms, so that is the expected floor plus batching).
- **Flaky test fixed**: `PaymentRateLimiterTest` used a 10 s period and failed once on a busy machine
  (class loading took 2 s, so refill had already started). It now uses a 1 h period.

## Packaging, deployment, CI and GenAI (M6)
- **Dockerfile**: multi-stage. `maven:3.9.9-eclipse-temurin-21` builds with a BuildKit cache mount
  for `~/.m2` and `-Dmaven.test.skip=true`, because tests run in CI, not in the image build. The runtime is
  `eclipse-temurin:21-jre-alpine`, with the Boot jar extracted into layers (dependencies first, so a
  code change only replaces a ~350 kB layer), the `JarLauncher` entrypoint, numeric UID 10001 (so
  Kubernetes can check `runAsNonRoot`), `MaxRAMPercentage=75`, `ExitOnOutOfMemoryError`, ECS JSON
  logs and a `wget` healthcheck on the liveness probe. Smoke-tested: the container ran as `uid=10001`,
  was reported healthy, and ran a payment plus a category lookup against compose Postgres/Redpanda.
- **Helm chart** (`deploy/helm/core-banking-ledger`): Deployment, Service, ConfigMap, and a Secret
  that is a *placeholder* (`CHANGE_ME`), skipped when `secret.existingSecret` is set. The chart deploys only
  the app; Postgres and Kafka are expected to be managed services or their own charts. Readiness,
  liveness and startup probes use the actuator probe groups (the startup probe allows 3 min for
  Flyway). The pod runs non-root with seccomp RuntimeDefault, a read-only root filesystem (with a `/tmp`
  emptyDir for Tomcat), all capabilities dropped and no privilege escalation. A config checksum
  annotation rolls the pods on config changes. Tracing sampling defaults to 10% in the chart.
  Validated with `helm lint` and `helm template | kubeconform -strict` (4/4 valid), run through the
  `alpine/helm:3.16.3` and `kubeconform:v0.6.7` images (no sudo install needed).
- **CI** (`.github/workflows/ci.yml`, no secrets): `mvn -B verify` on ubuntu-latest (Testcontainers
  uses the runner's Docker) uploads the surefire/failsafe reports and the concurrency CSV. Then
  `docker build` plus a Trivy scan (CRITICAL/HIGH, **report-only** until the baseline is triaged), and
  `helm lint` + kubeconform. Deploying is left out: there is no cluster to deploy to without credentials.
- **GenAI transaction categoriser behind a feature flag**: `genai.categoriser.enabled`
  (`GENAI_CATEGORISER_ENABLED`, default false). When it is off, neither the beans nor the endpoint
  exist (404). `GET /payments/{id}/category` applies the same ownership rule as reading the payment.
  - Providers: `mock` (default) uses deterministic keyword rules, needs no network, and is used by every
    test. `anthropic` calls Claude through the official `anthropic-java` SDK (3.3.0), with model
    `claude-opus-5-5` by default (`GENAI_CATEGORISER_MODEL`), low effort and `max_tokens` 1024 (thinking
    cannot be disabled on that model, so the budget leaves room for it). The key comes only from
    `ANTHROPIC_API_KEY` in the environment (a Secret in the chart).
  - **Fail closed to OTHER**: the answer must be exactly one label from a closed enum. Anything else
    becomes `OTHER`: extra text, a refusal, a truncated reply or an API error. A category is
    advisory and must never block or change money movement, so the categoriser never throws.
  - **Prompt injection**: the payment reference is customer-written. It is fenced in `<reference>`
    tags (with `<` escaped so it cannot close the fence), and the system prompt says it is data. The
    strict enum parse is the real control: an injected instruction can at worst pick a wrong label.
  - **Data minimisation**: the model receives only reference, amount and currency, never account
    ids, owners or balances.
  - Tests: `MockCategoriserTest` (rules, determinism, strict parse), `AnthropicCategoriserTest` (SDK
    client mocked with Mockito; accepts an exact label; injection text, refusal, truncation and API
    errors give OTHER; checks that the request fences the reference), and `CategoriserFlagTest`
    (off by default, mock when enabled, an unknown provider fails startup).
  - Not done: statement Q&A (the second half of the brief's GenAI item); server-side refusal
    fallbacks (a refusal already maps to OTHER); and an IT for the endpoint with the flag on,
    which would need another Spring context.
- **Reproduce**: `scripts/reproduce.sh` runs `mvn verify` (copies `app/target/concurrency_it.csv` to
  `results/`), then `scripts/run-k6.sh`, so it regenerates every file under `results/`.

## Saga compensation and crash recovery (post-M6 review fixes)
- **Compensation is registered per ledger call, not per step.** Each saga step is split into the
  participant call (`effect`) and its bookkeeping (`record`, i.e. `store.progress`). The step is
  pushed onto the compensation stack as soon as the ledger call returns, so a failure while
  recording still releases the hold / reverses the transfer. Before this, a `progress` failure after
  `captureHold` ended the payment `COMPENSATED` with the money still moved. Tests:
  `PaymentSagaTest.progressFailureAfter*` (mocks) and `SagaRecoveryIT.progressFailureAfter*` (real
  ledger: balances unchanged, no active holds, audit clean).
- **Recovery job (`SagaRecovery`)**: every `payments.recovery.interval-ms` (30 s) it claims payments
  still `PENDING`/`RESERVED`/`POSTED` with no progress for `payments.recovery.timeout` (default 5 min),
  one per transaction, with `SELECT ... FOR UPDATE SKIP LOCKED`, so any number of instances can run
  it. Compensation is idempotent: release every hold of the payment (`releaseHold` is a no-op on a
  non-active hold), then reverse the entry of any captured hold that has no reversal yet. The payment
  ends `COMPENSATED` (something was undone) or `FAILED` (nothing happened), with a PaymentFailed outbox
  event, and its idempotency key is completed with the 422 body the controller would have sent, so a
  retry replays it instead of getting 409. Recovery never rolls a saga forward: a crashed payment is
  always undone, which is the conservative choice for money and avoids double-posting.
- **Finding what a dead saga did**: the saga may die between a ledger call and `progress`, so
  `payments.hold_id`/`entry_id` are not enough. V5 adds `holds.reference` (`payment:<id>`, set by the
  saga through a new `placeHold` overload) and `holds.entry_id` (set by `captureHold`), so recovery
  finds the hold and the entry it became from the payment id alone.
- **Ledger calls join the recovery transaction** (`LedgerService` uses a `REQUIRED`
  `TransactionTemplate`), so the release/reversal, the status change, the outbox event and the key
  completion commit atomically, and the payment row lock is held throughout. If the ledger refuses a
  reversal (payee already spent the funds), the transaction rolls back and a fresh one marks the
  payment `COMPENSATION_FAILED` (key completed with 422) for an operator. Any other error aborts the
  pass and is retried next tick.
- **Slow, not dead, sagas**: if recovery takes over a saga that is still running, the two cannot both
  win: `captureHold` and `releaseHold` are conditional on the hold being `ACTIVE`, and
  `PaymentStore.progress`/`finish` now only update in-flight payments (first writer wins; `finish`
  writes the outbox event only if it changed the row). A late saga gets back the recovered payment.
  The timeout should be far above the saga's p99.9 duration; 5 minutes is.
- **Idempotency keys**: the controller now links the key to the payment (`attach`) before the saga
  starts. The job also completes keys left `IN_PROGRESS` for payments that did finish (crash between
  `finish` and storing the response), and deletes stale keys that never got a payment (crash before
  any money moved), so the retry simply runs again.
- Tests: `SagaRecoveryIT` simulates crashes by writing a mid-saga state into Postgres and ageing it:
  hold placed but not recorded, transfer captured but not recorded, nothing done, unlinked key, and
  4 concurrent recovery runs over 10 stale payments (each compensated exactly once, audit clean).
- Not done: alerting on `bank.saga.recovered{outcome="COMPENSATION_FAILED"}`; keys still have no TTL.

## Known gaps (to address in later runs)
- Idempotency keys never expire (they need a TTL job). (Per-client scoping was added in M3.)
- A reversal can fail if the payee has already spent the funds (the overdraft rule applies). The saga then reports
  `COMPENSATION_FAILED` (the saga and the recovery job alike). Real banks handle this with a receivable or manual process.
- Postgres and the broker are not covered by one transaction. That is the point of the outbox, but the relay still adds
  up to one polling interval of latency.

## Final-round checks (2026-11-16)
- **Quick start runs the jar, not `mvn -pl app spring-boot:run`.** With `-pl app` (and no `-am`)
  Maven resolves `common`, `ledger`, `payments`... from `~/.m2`, which is empty on a fresh clone
  (build fails) and stale on a machine that once ran `mvn install` (it silently ran old code).
  `mvn -B verify` already builds `app/target/app-0.1.0-SNAPSHOT.jar`, so the README runs that.
- jq added to the README prerequisites (the demo uses it).
