// k6 load profile for Fineract savings deposits.
//
// TWO DESIGN RULES, both forced by what the fork actually does:
//
// 1. EVERY VU OWNS A DISTINCT ACCOUNT. SavingsAccount carries @Version
//    (SavingsAccount.java:142) and a command conflict is RETRIED with 1s then
//    2s of backoff (application.properties:470-473). Two VUs sharing an account
//    would spend their time asleep, and the run would report ~1 TPS about the
//    test rather than anything about Fineract.
//
// 2. RAMPING ARRIVAL RATE, NOT VUS. `ramping-arrival-rate` is an OPEN model: it
//    holds the REQUEST RATE steady and adds VUs as needed. A closed model
//    (`ramping-vus`) sends the next request only after the previous one
//    returns, so when the server slows the load politely slows with it — the
//    load generator hides the very degradation you are trying to measure
//    (coordinated omission). The open model keeps offering the target rate and
//    lets the queue grow, which is what production traffic does.
//
// Amounts are deliberately tiny and deposits-only by default: this creates
// money in a staging ledger, and a large synthetic balance is confusing later.
import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import encoding from 'k6/encoding';

const ACCOUNTS = new SharedArray('accounts', () =>
  open(__ENV.ACCOUNTS_FILE || '/accounts.txt').split('\n').filter((l) => l.trim())
);

const BASE = __ENV.FINERACT_URL;
const TENANT = __ENV.FINERACT_TENANT || 'default';
const USER = __ENV.MW_WRITE_USER || 'innbucks-mw-write';
const PASS = __ENV.MW_WRITE_PASSWORD;
const PAYMENT_TYPE_ID = parseInt(__ENV.PAYMENT_TYPE_ID, 10);
const MODE = __ENV.MODE || 'smoke';
const TARGET_RATE = parseInt(__ENV.TARGET_RATE || '50', 10);
const MAX_VUS = parseInt(__ENV.MAX_VUS || '50', 10);

// Conflicts are invisible in a plain success/failure count: Fineract retries
// them internally and eventually returns 200, so they show up only as latency.
//
// READ THIS BEFORE INTERPRETING THE COUNTER. It is a proxy — literally
// "took longer than the 1s retry backoff" — and it is ONLY meaningful while
// MEDIAN latency stays well under 1s. Once the cell saturates and the median
// itself passes 1s, this counts nearly every request and says nothing: a run
// with a 2.1s median reported 90% "conflicts" and there were none.
//
// And in this test there cannot be any: rule 1 above gives every VU a DISTINCT
// account and a VU runs its iterations sequentially, so two concurrent requests
// against one account are structurally impossible. A high count here is
// evidence of a slow cell, not a contended one. RESULT.md states the same
// precondition.
const conflicts = new Counter('fineract_conflict_suspected');
const postLatency = new Trend('deposit_latency', true);

export const options =
  MODE === 'smoke'
    ? { scenarios: { smoke: { executor: 'shared-iterations', vus: 1, iterations: 10, maxDuration: '1m' } } }
    : {
        scenarios: {
          ramp: {
            executor: 'ramping-arrival-rate',
            startRate: 5,
            timeUnit: '1s',
            preAllocatedVUs: Math.min(MAX_VUS, ACCOUNTS.length),
            maxVUs: Math.min(MAX_VUS, ACCOUNTS.length),
            stages: [
              { target: 5, duration: '1m' },                        // warmup — EXCLUDED from the result
              { target: Math.ceil(TARGET_RATE * 0.25), duration: '2m' },
              { target: Math.ceil(TARGET_RATE * 0.5), duration: '2m' },
              { target: Math.ceil(TARGET_RATE * 0.75), duration: '2m' },
              { target: TARGET_RATE, duration: '3m' },              // steady state — THIS is the number
              { target: 0, duration: '30s' },
            ],
          },
        },
        thresholds: {
          // Not pass/fail gates so much as markers on the report: where these
          // break is where the cell stopped keeping up.
          http_req_failed: ['rate<0.01'],
          deposit_latency: ['p(95)<2000'],
        },
      };

export function setup() {
  if (!BASE || !PASS) throw new Error('FINERACT_URL and MW_WRITE_PASSWORD are required');
  if (!ACCOUNTS.length) throw new Error('no accounts loaded — run 10-fixtures.sh first');
  if (Number.isNaN(PAYMENT_TYPE_ID)) throw new Error('PAYMENT_TYPE_ID is required (Fineract validates it notNull on every deposit)');
  console.log(`mode=${MODE} accounts=${ACCOUNTS.length} targetRate=${TARGET_RATE}/s maxVUs=${Math.min(MAX_VUS, ACCOUNTS.length)}`);
  return { today: new Date().toISOString().slice(0, 10) };
}

export default function (data) {
  // Distinct account per VU. __VU is 1-based and stable for the VU's lifetime.
  const account = ACCOUNTS[(__VU - 1) % ACCOUNTS.length];
  const ref = `lt-${__VU}-${__ITER}-${Date.now()}`;

  const url = `${BASE}/v1/savingsaccounts/external-id/${encodeURIComponent(account)}/transactions?command=deposit`;
  const body = JSON.stringify({
    transactionDate: data.today,
    transactionAmount: 1.0,
    externalId: ref,
    paymentTypeId: PAYMENT_TYPE_ID,
    locale: 'en',
    dateFormat: 'yyyy-MM-dd',
  });

  const res = http.post(url, body, {
    headers: {
      'Content-Type': 'application/json',
      'Fineract-Platform-TenantId': TENANT,
      Authorization: `Basic ${encoding.b64encode(`${USER}:${PASS}`)}`,
    },
    tags: { name: 'savings-deposit' },
    timeout: '30s',
  });

  postLatency.add(res.timings.duration);

  // A response slower than the retry backoff almost certainly WAS a retry:
  // the first attempt collided, slept 1s, and succeeded on the second. Counting
  // these is what distinguishes lock contention from a slow core.
  if (res.status === 200 && res.timings.duration > 1000) conflicts.add(1);

  check(res, {
    'deposit accepted': (r) => r.status === 200,
    'not rejected for funds/validation': (r) => r.status !== 403,
  });
}
