#!/usr/bin/env bash
# Runs the k6 payments load test against a freshly started app and records:
#   results/k6_payments.json, results/k6_payments.md   (k6 handleSummary)
#   results/k6_prometheus_check.json                    (custom metrics scraped mid-run)
#   results/k6_ledger_audit.json                        (GET /ledger/audit after the run)
#
# Needs Docker, Java 21 and Maven. Starts postgres, redpanda and the observability profile, runs the
# app jar on the host, runs grafana/k6 in Docker (reaching the host via host-gateway), then stops the app.
# Containers are stopped at the end unless KEEP_STACK=1.
# Env: VUS (default 20), DURATION (default 60s), MACHINE (default: detected CPU/RAM).
set -euo pipefail
cd "$(dirname "$0")/.."

VUS="${VUS:-20}"
DURATION="${DURATION:-60s}"
K6_IMAGE="grafana/k6:0.54.0"
APP_LOG="app/target/k6-app.log"
MACHINE="${MACHINE:-$(grep -m1 'model name' /proc/cpuinfo | cut -d: -f2 | xargs), $(nproc) threads, $(free -g | awk '/Mem:/{print $2}') GB RAM, $(uname -sr)}"

APP_PID=""
cleanup() {
  [[ -n "$APP_PID" ]] && kill "$APP_PID" 2>/dev/null && wait "$APP_PID" 2>/dev/null || true
  if [[ "${KEEP_STACK:-0}" != "1" ]]; then docker compose --profile observability stop >/dev/null; fi
}
trap cleanup EXIT

echo "== infrastructure"
docker compose --profile observability up -d --wait

echo "== build"
JAR=$(ls app/target/app-*.jar 2>/dev/null | grep -v -e original -e stubs | head -1 || true)
if [[ -z "$JAR" || "${REBUILD:-0}" == "1" ]]; then
  mvn -q -B -DskipTests package
  JAR=$(ls app/target/app-*.jar | grep -v -e original -e stubs | head -1)
fi

echo "== start app ($JAR)"
# Rate limit lifted: this measures the payment path, not the limiter (RateLimitIT covers that).
PAYMENTS_RATE_CAPACITY=100000000 TRACING_ENABLED=true \
  java -Xmx512m -jar "$JAR" --spring.profiles.active=dev >"$APP_LOG" 2>&1 &
APP_PID=$!
for _ in $(seq 1 90); do
  curl -sf http://localhost:8080/actuator/health/readiness >/dev/null && break
  kill -0 "$APP_PID" 2>/dev/null || { echo "app died; see $APP_LOG"; exit 1; }
  sleep 1
done
curl -sf http://localhost:8080/actuator/health/readiness >/dev/null || { echo "app not ready"; exit 1; }

# Mid-run, ask Prometheus for the custom metrics so the evidence comes from the real scrape.
prom_check() {
  sleep "$1"
  local q out="results/k6_prometheus_check.json" tmp
  tmp=$(mktemp -d)
  local i=0
  for q in 'sum by (outcome) (bank_payments_total)' \
           'sum by (outcome) (bank_saga_duration_seconds_count)' \
           'histogram_quantile(0.99, sum by (le) (rate(bank_saga_duration_seconds_bucket{outcome="COMPLETED"}[30s])))' \
           'bank_outbox_lag_seconds' 'bank_outbox_pending' \
           'histogram_quantile(0.99, sum by (le) (rate(bank_outbox_publish_delay_seconds_bucket[30s])))'; do
    i=$((i + 1))
    printf '%s' "$q" >"$tmp/$i.q"
    curl -sfG http://localhost:9090/api/v1/query --data-urlencode "query=$q" >"$tmp/$i.json" || true
  done
  python3 - "$tmp" "$out" <<'PY'
import json, sys, glob, datetime
rows = []
for f in sorted(glob.glob(sys.argv[1] + "/*.json")):
    query = open(f[:-5] + ".q").read()
    try:
        d = json.load(open(f))
    except ValueError:
        d = {}
    for r in d.get("data", {}).get("result", []):
        rows.append({"query": query, "labels": r["metric"], "value": r["value"][1]})
json.dump({"queried_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
           "source": "Prometheus /api/v1/query during the k6 run (scripts/run-k6.sh)",
           "series": rows}, open(sys.argv[2], "w"), indent=2)
print(f"prometheus check: {len(rows)} series")
PY
  rm -rf "$tmp"
}
DUR_S="${DURATION%s}"
prom_check $(( DUR_S * 2 / 3 )) &
CHECK_PID=$!

echo "== k6 ($VUS VUs, $DURATION)"
docker run --rm --add-host host.docker.internal:host-gateway --user "$(id -u):$(id -g)" \
  -v "$PWD/scripts/k6:/scripts:ro" -v "$PWD/results:/results" \
  -e BASE_URL=http://host.docker.internal:8080 -e VUS="$VUS" -e DURATION="$DURATION" -e MACHINE="$MACHINE" \
  "$K6_IMAGE" run --quiet /scripts/payments.js || echo "k6 exited non-zero (threshold breached?)"
wait "$CHECK_PID" || true

echo "== ledger audit after the run"
TOKEN=$(curl -sf -X POST localhost:8080/dev/token -H 'Content-Type: application/json' \
  -d '{"subject":"k6-admin","roles":["ADMIN"]}' | python3 -c 'import json,sys;print(json.load(sys.stdin)["access_token"])')
curl -sf localhost:8080/ledger/audit -H "Authorization: Bearer $TOKEN" | tee results/k6_ledger_audit.json; echo
