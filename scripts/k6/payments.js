// k6 load test for POST /payments through the full stack: JWT auth, ownership check, rate-limit
// interceptor, idempotency claim, saga (hold -> capture -> COMPLETED + outbox) and the response.
//
// Run via scripts/run-k6.sh (grafana/k6 Docker image, app reached via host.docker.internal). Env:
//   BASE_URL   app URL (default http://localhost:8080; the app must run with the dev profile)
//   VUS        concurrent virtual users (default 20)
//   DURATION   test length (default 60s)
//   OUT_DIR    where handleSummary writes k6_payments.json / .md (default /results)
//   MACHINE    free-text machine description recorded with the numbers
//
// Each VU is its own customer (own JWT subject) paying from its own account to random other
// customers' accounts. Accounts are funded generously so failures mean errors, not insufficient
// funds. 5% of iterations resend the previous request with the same Idempotency-Key to exercise
// the replay path; a replay must return the stored 201 response.
import http from 'k6/http';
import { check, fail } from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const VUS = parseInt(__ENV.VUS || '20', 10);
const DURATION = __ENV.DURATION || '60s';
const OUT_DIR = __ENV.OUT_DIR || '/results';

export const options = {
  scenarios: {
    payments: { executor: 'constant-vus', vus: VUS, duration: DURATION },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    // Tagged sub-metrics so setup traffic is excluded from the reported numbers.
    'http_req_duration{name:create_payment}': ['p(99)<2000'],
    'http_req_failed{name:create_payment}': ['rate<0.01'],
    'http_reqs{name:create_payment}': ['count>0'],
  },
};

const JSON_HEADERS = { 'Content-Type': 'application/json' };

function token(subject, roles) {
  const r = http.post(`${BASE}/dev/token`, JSON.stringify({ subject, roles }), {
    headers: JSON_HEADERS, tags: { name: 'setup' },
  });
  if (r.status !== 200) fail(`token for ${subject}: ${r.status} ${r.body}`);
  return r.json('access_token');
}

export function setup() {
  const admin = token('k6-admin', ['ADMIN']);
  const auth = { headers: { ...JSON_HEADERS, Authorization: `Bearer ${admin}` }, tags: { name: 'setup' } };
  const run = Date.now(); // unique owners per run, so reruns never share accounts
  const customers = [];
  for (let i = 0; i < VUS; i++) {
    const owner = `k6-${run}-${i}`;
    const acc = http.post(`${BASE}/accounts`, JSON.stringify({ owner, currency: 'SGD', overdraftLimit: 0 }), auth);
    if (acc.status !== 201) fail(`open account: ${acc.status} ${acc.body}`);
    const id = acc.json('id');
    const dep = http.post(`${BASE}/accounts/${id}/deposits`, JSON.stringify({ amount: 1000000000 }), auth);
    if (dep.status !== 201 && dep.status !== 200) fail(`deposit: ${dep.status} ${dep.body}`);
    customers.push({ owner, account: id, token: token(owner, ['CUSTOMER']) });
  }
  return { customers };
}

let last = null; // per-VU: the previous request, for idempotent replays

export default function (data) {
  const me = data.customers[(__VU - 1) % data.customers.length];
  let key;
  let body;
  if (last !== null && Math.random() < 0.05) {
    ({ key, body } = last);
  } else {
    let to = me;
    while (to.account === me.account) {
      to = data.customers[Math.floor(Math.random() * data.customers.length)];
    }
    key = `${me.owner}-${__ITER}`;
    body = JSON.stringify({ fromAccount: me.account, toAccount: to.account, amount: 1 + Math.floor(Math.random() * 1000), currency: 'SGD', reference: 'k6 load test' });
    last = { key, body };
  }
  const r = http.post(`${BASE}/payments`, body, {
    headers: { ...JSON_HEADERS, Authorization: `Bearer ${me.token}`, 'Idempotency-Key': key },
    tags: { name: 'create_payment' },
  });
  check(r, { 'payment completed (201)': (res) => res.status === 201 });
}

function metric(data, name) {
  const m = data.metrics[name];
  return m ? m.values : {};
}

export function handleSummary(data) {
  const d = metric(data, 'http_req_duration{name:create_payment}');
  const reqs = metric(data, 'http_reqs{name:create_payment}');
  const failed = metric(data, 'http_req_failed{name:create_payment}');
  const seconds = data.state.testRunDurationMs / 1000;
  const result = {
    test: 'POST /payments (auth + idempotency + saga), constant VUs',
    timestamp: new Date().toISOString(),
    machine: __ENV.MACHINE || 'unspecified',
    vus: VUS,
    duration: DURATION,
    requests: reqs.count,
    throughput_rps: Number((reqs.count / seconds).toFixed(1)),
    error_rate: failed.rate,
    latency_ms: {
      avg: d.avg, p50: d.med, p90: d['p(90)'], p95: d['p(95)'], p99: d['p(99)'], max: d.max,
    },
    checks_passed_rate: metric(data, 'checks').rate,
  };
  const r2 = (x) => (x === undefined ? 'n/a' : x.toFixed(2));
  const md = `# k6 load test: POST /payments

Produced by \`scripts/run-k6.sh\` (k6 \`scripts/k6/payments.js\`) on ${result.timestamp}.

- Machine: ${result.machine}
- Load: ${VUS} constant VUs for ${DURATION}, each VU a separate customer (own JWT), 5% idempotent replays
- App: single instance, dev profile, PESSIMISTIC locking, tracing on (100% sampling, OTLP to Jaeger), rate limit lifted

| metric | value |
|---|---|
| requests | ${result.requests} |
| throughput | ${result.throughput_rps} req/s |
| error rate | ${(result.error_rate * 100).toFixed(2)}% |
| p50 | ${r2(d.med)} ms |
| p95 | ${r2(d['p(95)'])} ms |
| p99 | ${r2(d['p(99)'])} ms |
| max | ${r2(d.max)} ms |

Raw numbers: \`k6_payments.json\`. Prometheus check during the run: \`k6_prometheus_check.json\`.
`;
  return {
    [`${OUT_DIR}/k6_payments.json`]: JSON.stringify(result, null, 2) + '\n',
    [`${OUT_DIR}/k6_payments.md`]: md,
    stdout: `\n${md}\n`,
  };
}
