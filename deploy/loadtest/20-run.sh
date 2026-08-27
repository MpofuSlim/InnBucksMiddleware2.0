#!/usr/bin/env bash
# STAGE 3: run the load and collect enough context to say WHY it stopped there.
#
# Runs k6 from the official image over Docker, so nothing is installed on the
# box, and joins the private cell network so the load reaches Fineract directly
# rather than through the edge.
#
# TLS IS VERIFIED, NOT SKIPPED. k6 is Go, and Go honours SSL_CERT_FILE — so the
# cell CA is mounted and pointed at, rather than reaching for
# --insecure-skip-tls-verify. A load test is not a reason to practise ignoring
# certificates.
#
# THE GENERATOR RUNS ON THE SAME BOX AS THE THING IT MEASURES. That is a real
# constraint, not a detail: k6 competes with Fineract and both Postgres
# instances for the same cores. This script pins k6 away from the others where
# it can, samples everyone's CPU during the run, and reports the generator's own
# usage next to the server's — because if k6 is pegged and Fineract is not, the
# number is k6's, not Fineract's.
#
# Usage:
#   cd ~/InnBucksMiddleware2.0/deploy/fineract
#   export MW_WRITE_PASSWORD='...' ADMIN_PASSWORD='...'
#   ../loadtest/20-run.sh                        # smoke: 1 VU, 10 requests
#   ../loadtest/20-run.sh --mode full            # the ramp
#   ../loadtest/20-run.sh --mode full --rate 80 --max-vus 40
set -euo pipefail

MODE=smoke
TARGET_RATE=50
MAX_VUS=50
while [ $# -gt 0 ]; do
    case "$1" in
        --mode)     MODE="$2"; shift 2 ;;
        --rate)     TARGET_RATE="$2"; shift 2 ;;
        --max-vus)  MAX_VUS="$2"; shift 2 ;;
        *) echo "unknown option: $1" >&2; exit 1 ;;
    esac
done

TENANT="${FINERACT_TENANT:-default}"
ACCOUNTS="${ACCOUNTS_FILE:-/tmp/fineract-loadtest-accounts.txt}"
CA="${CA_CERT:-$(pwd)/ssl/cell-ca.crt}"
NET="${CELL_NETWORK:-innbucks-cell-shared}"
FINERACT_IN_NET="${FINERACT_INTERNAL_URL:-https://fineract:8443/fineract-provider/api}"
OUTDIR="${OUTDIR:-/tmp/fineract-loadtest-$(date -u +%Y%m%dT%H%M%SZ)}"
FINERACT_DB_CTR="innbucks-fineract-db"

# Pinned by DIGEST, like every other image in this cell — the compose header
# says "never :latest" and the measurement tool had been the one exception. A
# floating tag means a re-run months later silently compares two k6 versions
# and calls the difference a Fineract change.
#
# This is the digest that produced the 2026-08-27 baseline runs. To move it:
#   docker pull grafana/k6:latest && docker inspect --format='{{index .RepoDigests 0}}' grafana/k6:latest
# and say in the commit message which runs the old digest backs.
K6_IMAGE="${K6_IMAGE:-grafana/k6@sha256:5221b620a4f874faff6e32ba597aa667c058391fe4898b1c6f6377f062c6cdec}"

log()  { printf '>> %s\n' "$*" >&2; }
fail() { printf 'FATAL: %s\n' "$*" >&2; exit 1; }

# Log parsers live in their own file so selftest.sh can exercise the REAL
# functions against synthetic logs with known answers, rather than a copy that
# drifts. Two of them shipped broken before that existed.
LT_LIB="$(dirname "${BASH_SOURCE[0]}")/lib-parse.sh"
[ -f "$LT_LIB" ] || fail "missing $LT_LIB — run the script from its own checkout"
# shellcheck source=lib-parse.sh
. "$LT_LIB"

: "${MW_WRITE_PASSWORD:?export MW_WRITE_PASSWORD}"
[ -f "$ACCOUNTS" ] || fail "no accounts file at $ACCOUNTS — run 10-fixtures.sh first"
[ -f "$CA" ]       || fail "no CA at $CA — run from deploy/fineract/, or set CA_CERT"
docker network inspect "$NET" >/dev/null 2>&1 || fail "docker network '$NET' not found"

ACCOUNT_COUNT=$(wc -l < "$ACCOUNTS" | tr -d ' ')
mkdir -p "$OUTDIR"

# Mirrors the slice arithmetic in deposit-load.js so RESULT.md can state how
# many accounts the run actually touched. Keep lt_slice and the JS in step: if
# one changes, the report describes a run that did not happen.
VUS_EFF=$(( MAX_VUS < ACCOUNT_COUNT ? MAX_VUS : ACCOUNT_COUNT ))
read -r SLICE ACCOUNTS_TOUCHED <<<"$(lt_slice "$ACCOUNT_COUNT" "$MAX_VUS")" || true

# --- Guard rails ------------------------------------------------------------
# Concurrency above the number of accounts means VUs start sharing accounts,
# and a shared account means optimistic-lock retries with 1s/2s backoff
# (application.properties:470-473) — the result would describe the collision,
# not the core.
if [ "$MODE" = full ] && [ "$MAX_VUS" -gt "$ACCOUNT_COUNT" ]; then
    fail "max-vus ($MAX_VUS) exceeds accounts ($ACCOUNT_COUNT).
     VUs would share accounts and measure lock-retry backoff instead of throughput.
     Either lower --max-vus or create more: ../loadtest/10-fixtures.sh $MAX_VUS"
fi

POOL=$(docker exec "$FINERACT_DB_CTR" psql -U fineract -d fineract_tenants -At -c "
  SELECT ts.pool_max_active FROM tenants t
  JOIN tenant_server_connections ts ON ts.id = t.oltp_id
  WHERE t.identifier = '${TENANT}';" 2>/dev/null | tr -d ' ')
if [ -z "$POOL" ]; then
    # Say so rather than skip quietly: a guard whose input could not be read
    # fails OPEN, and the run would then happily ramp past the pool and report
    # its queue as Fineract's ceiling.
    log "WARNING: could not read tenant pool_max_active — the pool guard below is NOT active."
    log "         Check it by hand before trusting a plateau:"
    log "           docker exec $FINERACT_DB_CTR psql -U fineract -d fineract_tenants -c \\"
    log "             'SELECT t.identifier, ts.pool_max_active FROM tenants t"
    log "              JOIN tenant_server_connections ts ON ts.id = t.oltp_id;'"
elif [ "$MODE" = full ] && [ "$MAX_VUS" -gt "$POOL" ]; then
    log "WARNING: max-vus ($MAX_VUS) exceeds the tenant pool_max_active ($POOL)."
    log "         Past the pool, extra VUs queue for a connection: latency climbs,"
    log "         throughput stays flat. That plateau is the POOL, not Fineract."
fi

# Hikari's minimumIdle for the tenant pool comes from pool_initial_size
# (DataSourcePerTenantServiceFactory:92 -> TenantMapper:34), NOT pool_min_idle,
# which is never applied. It is read separately from the guard query above so a
# failure here can never disarm that guard. Reported so the warmup caveat in
# RESULT.md can state the actual growth span instead of hand-waving it.
POOL_INIT=$(docker exec "$FINERACT_DB_CTR" psql -U fineract -d fineract_tenants -At -c "
  SELECT ts.pool_initial_size FROM tenants t
  JOIN tenant_server_connections ts ON ts.id = t.oltp_id
  WHERE t.identifier = '${TENANT}';" 2>/dev/null | tr -d ' ')

PAYMENT_TYPE_ID="${PAYMENT_TYPE_ID:-}"
if [ -z "$PAYMENT_TYPE_ID" ]; then
    : "${ADMIN_PASSWORD:?export ADMIN_PASSWORD (or PAYMENT_TYPE_ID) so the deposit body can be built}"
    PAYMENT_TYPE_ID=$(curl -sS --cacert "$CA" -u "${ADMIN_USER:-mifos}:${ADMIN_PASSWORD}" \
        -H "Fineract-Platform-TenantId: ${TENANT}" \
        "${FINERACT_URL:-https://localhost:8443/fineract-provider/api}/v1/paymenttypes" \
        | jq -r --arg n "${PAYMENT_TYPE_NAME:-InnBucks Wallet}" \
            '[.. | objects | select(.name? == $n)] | .[0].id // empty')
    [ -n "$PAYMENT_TYPE_ID" ] || fail "could not resolve paymentTypeId — Fineract rejects every deposit without it"
fi

# --- Baseline -------------------------------------------------------------
log "mode=$MODE accounts=$ACCOUNT_COUNT pool_max_active=${POOL:-unknown} out=$OUTDIR"
docker exec "$FINERACT_DB_CTR" psql -U fineract -d "${TENANT_DB:-fineract_default}" -At -c \
    "SELECT count(*) FROM m_savings_account_transaction;" > "$OUTDIR/txn-count-before.txt" 2>/dev/null || true
nproc > "$OUTDIR/nproc.txt" 2>/dev/null || true

# --- Sample resource usage throughout --------------------------------------
# Without this you get a TPS number and no way to attribute it.
( while :; do
    printf '%s ' "$(date -u +%H:%M:%S)"
    docker stats --no-stream --format '{{.Name}}={{.CPUPerc}}/{{.MemPerc}}' 2>/dev/null | tr '\n' ' '
    printf '\n'
    sleep 5
  done ) > "$OUTDIR/docker-stats.log" 2>&1 &
STATS_PID=$!

# Fineract's own internals, sampled on the same cadence. CPU alone cannot tell
# GC pauses from lock waits from real work, and the pool gauges are INSTANTANEOUS
# — scraping them after the run only ever reports an idle cell. The first full
# run was diagnosed from a post-hoc scrape showing pending=0, which proved
# nothing about the ten minutes that mattered.
# Needs FINERACT_MANAGEMENT_PROMETHEUS_ENABLED=true (00-discover.sh probes it);
# with the export off this file is empty and the derived rows read "?".
FINERACT_LOCAL="${FINERACT_LOCAL_URL:-https://localhost:8443/fineract-provider}"
( while :; do
    printf '%s ' "$(date -u +%H:%M:%S)"
    { curl -sS --cacert "$CA" --max-time 4 "$FINERACT_LOCAL/actuator/prometheus" 2>/dev/null \
        | grep -E '^(jvm_gc_pause_seconds_(count|sum)|hikaricp_connections_(active|pending)|fineract_tenants_[a-z0-9_]+_hikaricp_connections_(active|pending))\{' \
        | tr '\n' ' '; } || true
    printf '\n'
    sleep 5
  done ) > "$OUTDIR/fineract-metrics.log" 2>&1 &
METRICS_PID=$!

trap 'kill "$STATS_PID" "$METRICS_PID" 2>/dev/null || true' EXIT

# --- Run k6 -----------------------------------------------------------------
# Pin k6 to the last two cores so it is not fighting Fineract for core 0.
CORES=$(nproc 2>/dev/null || echo 2)
if [ "$CORES" -ge 4 ]; then
    CPUSET="--cpuset-cpus=$((CORES-2)),$((CORES-1))"
    log "pinning k6 to cores $((CORES-2)),$((CORES-1)) of $CORES"
else
    CPUSET=""
    log "WARNING: only $CORES cores — k6 cannot be isolated from Fineract."
    log "         Treat the result as a FLOOR. The generator is in the measurement."
fi

log "starting k6 ($MODE) ..."
# The k6 image runs as its own uid (12345), so it cannot write into a directory
# the host user just created with default 0755 — --summary-export then fails
# with "permission denied" AFTER the run, when the data is already gone.
# World-writable is fine for a per-run artifact directory under /tmp, and it is
# the option that cannot break k6's startup the way --user might.
chmod a+rwx "$OUTDIR"
# --name is load-bearing: `docker stats` reports containers BY NAME, and the
# diagnosis below greps for the generator's CPU by that name. Without it Docker
# assigns a random name and the generator-bound row could never be filled in.
docker rm -f k6-loadtest >/dev/null 2>&1 || true
set +e
docker run --rm -i \
    --name k6-loadtest \
    --network "$NET" \
    $CPUSET \
    -v "$ACCOUNTS":/accounts.txt:ro \
    -v "$CA":/ca/cell-ca.crt:ro \
    -v "$OUTDIR":/out \
    -e SSL_CERT_FILE=/ca/cell-ca.crt \
    -e ACCOUNTS_FILE=/accounts.txt \
    -e FINERACT_URL="$FINERACT_IN_NET" \
    -e FINERACT_TENANT="$TENANT" \
    -e MW_WRITE_PASSWORD="$MW_WRITE_PASSWORD" \
    -e PAYMENT_TYPE_ID="$PAYMENT_TYPE_ID" \
    -e MODE="$MODE" \
    -e TARGET_RATE="$TARGET_RATE" \
    -e MAX_VUS="$MAX_VUS" \
    "$K6_IMAGE" run --summary-export=/out/k6-summary.json - \
    < "$(dirname "${BASH_SOURCE[0]}")/deposit-load.js" \
    2>&1 | tee "$OUTDIR/k6.log"
K6_RC=${PIPESTATUS[0]}
set -e
kill "$STATS_PID" "$METRICS_PID" 2>/dev/null || true

# --- After ------------------------------------------------------------------
docker exec "$FINERACT_DB_CTR" psql -U fineract -d "${TENANT_DB:-fineract_default}" -At -c \
    "SELECT count(*) FROM m_savings_account_transaction;" > "$OUTDIR/txn-count-after.txt" 2>/dev/null || true
BEFORE=$(cat "$OUTDIR/txn-count-before.txt" 2>/dev/null || echo 0)
AFTER=$(cat "$OUTDIR/txn-count-after.txt" 2>/dev/null || echo 0)

# --- Diagnosis --------------------------------------------------------------
# Every one of these can legitimately find nothing: docker stats samples every
# 5s, so a run shorter than that captures no line at all. Under the `set -euo
# pipefail` restored above, a grep that matches nothing fails the pipeline and
# `set -e` kills the script HERE — after the entire run, before RESULT.md is
# written. That is exactly what happened on the first smoke run: 10/10 requests
# succeeded and the report was never produced. The `|| true` is what makes a
# missing sample degrade to "?" in one table cell instead of losing the report.
S_LOG="$OUTDIR/docker-stats.log"
PEAK_K6=$(lt_peak_cpu "$S_LOG" 'k6-loadtest')
PEAK_FIN=$(lt_peak_cpu "$S_LOG" 'innbucks-fineract')   # the '=' anchor excludes -db
PEAK_PG=$(lt_peak_cpu "$S_LOG" 'innbucks-fineract-db')

# docker stats reports 100% == ONE core, so the whole box is CORES*100.
CPU_FULL=$(( CORES * 100 ))
# Mark the row the samples actually support. The first version of this table
# printed all six rows unconditionally with the peaks interpolated into the
# prose, so a row reading "k6 CPU pegged (peak 8.55%)" sat there looking like a
# verdict while k6 was in fact idle. A table of possibilities is not a
# diagnosis; the reader should not have to do the comparison the script can do.
verdict() {
    awk -v k6="${PEAK_K6:-}" -v fin="${PEAK_FIN:-}" -v pg="${PEAK_PG:-}" \
        -v full="$CPU_FULL" -v which="$1" 'BEGIN{
        if (k6 == "" && fin == "" && pg == "") { print "no sample"; exit }
        half = full / 2
        if      (which == "gen")  print (k6+0  >= half && k6+0  >  fin+0) ? "**YES**" : "no"
        else if (which == "pg")   print (pg+0  >  fin+0)                  ? "**YES**" : "no"
        else if (which == "fin")  print (fin+0 >= half && fin+0 >= pg+0)  ? "**YES**" : "no"
        else if (which == "idle") print (fin+0 < half && pg+0 < half && k6+0 < half) ? "**YES**" : "no"
    }'
}
V_GEN=$(verdict gen); V_PG=$(verdict pg); V_FIN=$(verdict fin); V_IDLE=$(verdict idle)

# From the Prometheus samples taken DURING the run. These answer the two
# verdicts CPU cannot: peak pending threads settles POOL-BOUND outright, and GC
# seconds settles "something is serialising" — the first full run spent 6.4s of
# 632s in GC (1%), which ruled out the heap that everyone reaches for first.
M_LOG="$OUTDIR/fineract-metrics.log"
OLTP_POOL=$(lt_pool_label "$M_LOG")
read -r PEAK_ACTIVE PEAK_PENDING PENDING_SAMPLES TOTAL_SAMPLES <<<"$(lt_pool_scan "$M_LOG")" || true
GC_SECS=$(lt_gc_secs "$M_LOG")

# --- k6's own summary -------------------------------------------------------
# --summary-export has been written since the first run and never read. Three
# of its numbers change how the result is read and all three were missing:
#
#   dropped_iterations  the open model DROPS iterations it cannot start rather
#                       than queueing them. Large here means the offered rate
#                       was never actually offered, so the run measured
#                       throughput AT THE VU CAP, not a ceiling.
#   http_reqs.rate      the achieved rate, so RESULT.md stops telling the
#                       reader to go dig it out of k6.log by hand.
#   tls handshaking     closes the "is TLS in the median?" question with a
#                       number instead of an argument (it is not: handshakes
#                       are counted under http_req_blocked, not req_duration).
SUMMARY="$OUTDIR/k6-summary.json"
REQS_RATE=$(lt_k6_metric "$SUMMARY" http_reqs rate)
REQS_COUNT=$(lt_k6_metric "$SUMMARY" http_reqs count)
DROPPED=$(lt_k6_metric "$SUMMARY" dropped_iterations count)
TLS_AVG=$(lt_k6_metric "$SUMMARY" http_req_tls_handshaking avg)
BLOCKED_AVG=$(lt_k6_metric "$SUMMARY" http_req_blocked avg)

# Offered vs achieved, so "the cell did 16.6/s against a 400/s target" reads as
# the 4% completion rate it is rather than as a throughput result.
OFFERED_PCT=$(awk -v r="${REQS_COUNT:-}" -v d="${DROPPED:-}" \
    'BEGIN { if (r == "" || d == "") { print "?" } else if (r + d == 0) { print "?" }
             else printf "%.1f%%", 100 * r / (r + d) }')

# Only meaningful when the file is bigger than the VU count — at slice 1 there
# is nothing to spread and "aged ~1x faster" reads as noise.
if [ "${SLICE:-1}" -gt 1 ]; then
    SLICE_NOTE="- Runs from before the VU-slice change used only the FIRST max_vus accounts,
  so they aged each one ~${SLICE}x faster per run. Do not compare across that
  change without checking the per-account history on both sides."
else
    SLICE_NOTE="- accounts == max_vus, so each VU has exactly one account and this run ages
  history at the maximum rate. Create more accounts to spread it."
fi

REQS_RATE_D=$(lt_num2u "${REQS_RATE:-}" "/s")
TLS_AVG_D=$(lt_num2u "${TLS_AVG:-}" "ms")
BLOCKED_AVG_D=$(lt_num2u "${BLOCKED_AVG:-}" "ms")

cat <<EOF | tee "$OUTDIR/RESULT.md"

# Fineract load test — $(date -u +%Y-%m-%dT%H:%M:%SZ)

mode=$MODE  accounts=$ACCOUNT_COUNT  target_rate=${TARGET_RATE}/s  max_vus=$MAX_VUS
accounts touched: $ACCOUNTS_TOUCHED of $ACCOUNT_COUNT ($SLICE per VU across $VUS_EFF VUs)
tenant pool: max_active=${POOL:-unknown} initial_size=${POOL_INIT:-unknown}  host cores=$CORES
k6 image: $K6_IMAGE
savings transactions written: $((AFTER - BEFORE))  (before=$BEFORE after=$AFTER)

## The number
whole-run achieved rate: ${REQS_RATE_D}
requests completed: ${REQS_COUNT:-?}  ·  iterations DROPPED: ${DROPPED:-?}  ·  offered load actually served: ${OFFERED_PCT}

**Read \`dropped_iterations\` before quoting anything above.** This is an OPEN
model: when k6 cannot start a scheduled iteration it DROPS it rather than
queueing, and it can only start one per free VU. So once every VU is busy, the
achieved rate is pinned at \`max_vus ÷ mean iteration time\` — an identity, not
a discovery — and the latency distribution is a closed-loop-at-${MAX_VUS} sample.
A large drop count means the target rate was never actually offered, and the run
measured **throughput at concurrency ${MAX_VUS}**, not a ceiling. To find the
ceiling, ladder max_vus (10/20/40/60/80) and watch where throughput stops
rising; the knee is usually well below the pool size.

Take the steady-state figure from k6.log rather than the whole-run rate above:
the first minute is warmup (JIT, page cache, and the connection pool growing
from ${POOL_INIT:-?} to ${POOL:-?} on demand as VUs arrive) and including it
understates throughput. Quote it with everything on the header lines above.

TLS is NOT in the latency number: handshaking averaged ${TLS_AVG_D} and
connect+handshake are reported under http_req_blocked (avg ${BLOCKED_AVG_D}),
which \`http_req_duration\` excludes. Connections are reused across iterations,
so this is a few dozen handshakes across the whole run — not a per-request cost.

## What limited it
Peak CPU, out of ${CPU_FULL}% for the whole box (docker stats: 100% = one core):
k6 ${PEAK_K6:-?}%  ·  Fineract ${PEAK_FIN:-?}%  ·  Postgres ${PEAK_PG:-?}%

| Fired | Verdict |
|---|---|
| $V_GEN | GENERATOR-BOUND — k6 outran the cell. Not a Fineract number; move the generator off this box. |
| $V_PG | POSTGRES-BOUND — the co-located DB outran Fineract. Separate it, or tune shared_buffers/work_mem. |
| $V_FIN | FINERACT-BOUND — the real core ceiling on this hardware. See the note on account history below before calling it a hardware limit. |
| $V_IDLE | Nothing was pegged and throughput was flat, so something is SERIALISING. Check GC pause time and heap (-Xmx) in the JVM metrics. |

From Fineract's own metrics, sampled every 5s DURING the run
(\`fineract-metrics.log\`; "?" means the Prometheus export was off):

pool: ${OLTP_POOL:-?} (the tenant OLTP pool — the money path, NOT the
tenants-store pool, which is a separate 10-connection pool serving one
cached SELECT and is never the constraint)

peak active ${PEAK_ACTIVE:-?} of ${POOL:-?}  ·  peak pending ${PEAK_PENDING:-?}  ·  samples with any thread waiting: ${PENDING_SAMPLES:-?} of ${TOTAL_SAMPLES:-?}  ·  GC during run ${GC_SECS:-?}s

- **Judge POOL-BOUND on the pending-sample COUNT, not the peak.** Peak active
  and peak pending are independent maxima taken over the whole run — they need
  never have co-occurred, so "40 active, 14 pending" from bare maxima is not
  evidence of anything. A handful of samples out of a hundred is a blip; most
  of them is a queue.
- **A saturated pool is not automatically the cause.** If Fineract's CPU is
  also pegged, connections are being HELD while Java burns CPU, and the pool is
  a symptom — raising \`pool_max_active\` then just adds more threads waiting on
  the same cores, and costs Postgres backends. Three runs on this cell showed
  exactly that: 40/40 with threads pending at BOTH 16.6/s and 12.7/s, with
  Fineract CPU identical. Raise the pool only when Fineract CPU has headroom.
- **GC seconds against the run's wall-clock is the serialising verdict.** A few
  percent is noise and rules the heap out; a large fraction means raise -Xmx
  before believing any other number here.

One more that CPU samples cannot decide for you:

- **LOCK-BOUND** if \`fineract_conflict_suspected\` is a large share of requests
  **AND median latency stayed well under 1s**. That second half is not optional.
  The counter is just "response took longer than the 1s retry backoff", so once
  the median itself exceeds 1s it counts nearly every request and means nothing.
  Note also that each VU owns a DISJOINT SLICE of accounts, so two concurrent
  requests against one account are structurally impossible in this test — a high
  count here with a slow median is a false positive, not evidence of contention.

## Caveats that belong in any report of this number
- Generator ran on the same box as the system under test. Check its CPU above:
  if k6 is low single digits, it is measurably NOT distorting the result.
- Per-deposit cost grows with account history (SavingsAccount.java:1008 walks
  every transaction on the account, on every posting). This run added
  $((AFTER - BEFORE)) rows across $ACCOUNTS_TOUCHED accounts — about
  $(( ACCOUNTS_TOUCHED > 0 ? (AFTER - BEFORE) / ACCOUNTS_TOUCHED : 0 )) each — so a
  repeat on the same accounts WILL be slower, and that is real production
  behaviour, not a test artifact. Quote the per-account transaction count
  alongside the TPS or the number is not reproducible.
- Fineract heap here is whatever 00-discover.sh reported, not a tuned value.
$SLICE_NOTE

Artifacts: $OUTDIR
EOF

[ "$K6_RC" -eq 0 ] || log "k6 exited $K6_RC (thresholds breached is a normal 'we found the limit' outcome)"
log "done — $OUTDIR/RESULT.md"
