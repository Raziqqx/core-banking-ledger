#!/usr/bin/env bash
# Regenerates every file in results/ from scratch:
#   results/concurrency_it.csv       mvn verify (ConcurrencyIT: 16 threads x 4000 ops, both locking
#                                    modes and the saga), copied from app/target/
#   results/k6_payments.{json,md}    scripts/run-k6.sh (k6 load test, 60 s)
#   results/k6_prometheus_check.json custom metrics queried from Prometheus during the k6 run
#   results/k6_ledger_audit.json     ledger audit after the k6 run
# Needs Docker, Java 21 and Maven. Containers are stopped at the end.
set -euo pipefail
cd "$(dirname "$0")/.."

echo "== mvn verify (unit, contract and Testcontainers integration tests)"
rm -f app/target/concurrency_it.csv
mvn -B -q verify
mkdir -p results
cp app/target/concurrency_it.csv results/concurrency_it.csv
echo "results/concurrency_it.csv:"; cat results/concurrency_it.csv

echo "== k6 load test"
scripts/run-k6.sh   # reuses the jar mvn verify just built
