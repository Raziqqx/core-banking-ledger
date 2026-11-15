# k6 load test: POST /payments

Produced by `scripts/run-k6.sh` (k6 `scripts/k6/payments.js`) on 2026-11-15T15:25:32.425Z.

- Machine: Intel(R) Core(TM) i5-14500HX, 20 threads, 7 GB RAM, Linux 6.6.114.1-microsoft-standard-WSL2
- Load: 20 constant VUs for 60s, each VU a separate customer (own JWT), 5% idempotent replays
- App: single instance, dev profile, PESSIMISTIC locking, tracing on (100% sampling, OTLP to Jaeger), rate limit lifted

| metric | value |
|---|---|
| requests | 23477 |
| throughput | 380.4 req/s |
| error rate | 0.00% |
| p50 | 50.17 ms |
| p95 | 70.10 ms |
| p99 | 85.88 ms |
| max | 319.39 ms |

Raw numbers: `k6_payments.json`. Prometheus check during the run: `k6_prometheus_check.json`.
