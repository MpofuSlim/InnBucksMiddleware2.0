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

log()  { printf '>> %s\n' "$*" >&2; }
fail() { printf 'FATAL: %s\n' "$*" >&2; exit 1; }

: "${MW_WRITE_PASSWORD:?export MW_WRITE_PASSWORD}"
[ -f "$ACCOUNTS" ] || fail "no accounts file at $ACCOUNTS — run 10-fixtures.sh first"
[ -f "$CA" ]       || fail "no CA at $CA — run from deploy/fineract/, or set CA_CERT"
docker network inspect "$NET" >/dev/null 2>&1 || fail "docker network '$NET' not found"

ACCOUNT_COUNT=$(wc -l < "$ACCOUNTS" | tr -d ' ')
mkdir -p "$OUTDIR"

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
trap 'kill "$STATS_PID" 2>/dev/null || true' EXIT

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
set +e
docker run --rm -i \
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
    grafana/k6:latest run --summary-export=/out/k6-summary.json - \
    < "$(dirname "${BASH_SOURCE[0]}")/deposit-load.js" \
    2>&1 | tee "$OUTDIR/k6.log"
K6_RC=${PIPESTATUS[0]}
set -e
kill "$STATS_PID" 2>/dev/null || true

# --- After ------------------------------------------------------------------
docker exec "$FINERACT_DB_CTR" psql -U fineract -d "${TENANT_DB:-fineract_default}" -At -c \
    "SELECT count(*) FROM m_savings_account_transaction;" > "$OUTDIR/txn-count-after.txt" 2>/dev/null || true
BEFORE=$(cat "$OUTDIR/txn-count-before.txt" 2>/dev/null || echo 0)
AFTER=$(cat "$OUTDIR/txn-count-after.txt" 2>/dev/null || echo 0)

# --- Diagnosis --------------------------------------------------------------
PEAK_K6=$(grep -oE 'k6[^ ]*=[0-9.]+%' "$OUTDIR/docker-stats.log" 2>/dev/null | grep -oE '[0-9.]+' | sort -rn | head -1)
PEAK_FIN=$(grep -oE 'innbucks-fineract=[0-9.]+%' "$OUTDIR/docker-stats.log" 2>/dev/null | grep -oE '[0-9.]+' | sort -rn | head -1)
PEAK_PG=$(grep -oE 'innbucks-fineract-db=[0-9.]+%' "$OUTDIR/docker-stats.log" 2>/dev/null | grep -oE '[0-9.]+' | sort -rn | head -1)

cat <<EOF | tee "$OUTDIR/RESULT.md"

# Fineract load test — $(date -u +%Y-%m-%dT%H:%M:%SZ)

mode=$MODE  accounts=$ACCOUNT_COUNT  target_rate=${TARGET_RATE}/s  max_vus=$MAX_VUS
tenant pool_max_active=${POOL:-unknown}  host cores=$CORES
savings transactions written: $((AFTER - BEFORE))  (before=$BEFORE after=$AFTER)

## The number
Take it from the STEADY-STATE stage in k6.log, not the whole run: the first
minute is warmup (JIT, pool fill, page cache) and including it understates
throughput. The figure to quote is \`http_reqs\` rate during the final
sustained stage, with everything on the line above stated alongside it.

## What limited it — read in this order
| Symptom | Verdict |
|---|---|
| k6 CPU pegged (peak ${PEAK_K6:-?}%), Fineract not | GENERATOR-BOUND. Not a Fineract number. Move the generator off this box. |
| \`fineract_conflict_suspected\` a large share of requests | LOCK-BOUND. VUs are colliding on accounts; add accounts, lower VUs. |
| Latency climbs, throughput flat, VUs > pool_max_active (${POOL:-?}) | POOL-BOUND. Raise tenant_server_connections.pool_max_active, or stay under it. |
| Postgres CPU pegged (peak ${PEAK_PG:-?}%), Fineract lower | POSTGRES-BOUND. Co-located DB; separate it or tune it. |
| Fineract CPU pegged (peak ${PEAK_FIN:-?}%), Postgres lower | FINERACT-BOUND. This is the real core ceiling on this hardware. |
| Nothing pegged, throughput flat | Something is serialising. Check GC in the JVM metrics, and heap (-Xmx). |

## Caveats that belong in any report of this number
- Generator ran on the same box as the system under test.
- Per-deposit cost grows with account history (SavingsAccount.java:1008 walks
  every transaction on the account). This run added $((AFTER - BEFORE)) rows —
  a repeat on the same accounts will be slower, and that is real.
- Fineract heap here is whatever 00-discover.sh reported, not a tuned value.

Artifacts: $OUTDIR
EOF

[ "$K6_RC" -eq 0 ] || log "k6 exited $K6_RC (thresholds breached is a normal 'we found the limit' outcome)"
log "done — $OUTDIR/RESULT.md"
