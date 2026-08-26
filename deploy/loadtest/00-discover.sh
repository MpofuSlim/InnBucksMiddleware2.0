#!/usr/bin/env bash
# STAGE 1 of the Fineract TPS test. READ-ONLY: creates nothing, changes
# nothing, writes nothing but a report. Run it first, every time.
#
# It exists because the numbers that decide whether a TPS result is meaningful
# are NOT in this repository:
#
#   * The per-tenant connection pool — the real ceiling on concurrent Fineract
#     writes — lives in the fineract_tenants DATABASE, not in config.
#     DataSourcePerTenantServiceFactory.getMaxPoolSize() only uses the
#     fineract.tenant.config.max-pool-size override when it is not -1, and -1 is
#     the default (application.properties:64). So the effective value comes from
#     tenant_server_connections.pool_max_active (TenantMapper.java:37).
#     Ramp past it and you measure the pool's queue, not Fineract.
#   * Fineract's Prometheus export is DISABLED by default
#     (application.properties:357) — without it you get a TPS number and no
#     idea what limited it.
#
# Usage:
#   cd ~/InnBucksMiddleware2.0/deploy/fineract
#   export ADMIN_PASSWORD='...'                # only for the optional API probe
#   ../loadtest/00-discover.sh
set -uo pipefail        # deliberately NOT -e: a failed probe must not abort the report

TENANT="${FINERACT_TENANT:-default}"
TENANT_DB="${TENANT_DB:-fineract_default}"
FINERACT_DB_CTR="innbucks-fineract-db"
FINERACT_CTR="innbucks-fineract"
MW_DB_CTR="innbucks-middleware-postgres"
REPORT="${1:-/tmp/fineract-loadtest-discovery.txt}"

hdr() { printf '\n\033[1m--- %s\033[0m\n' "$*"; }
kv()  { printf '  %-34s %s\n' "$1" "$2"; }

exec > >(tee "$REPORT") 2>&1

printf '\033[1mFineract load-test discovery — %s\033[0m\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"

hdr "Host"
kv "hostname"        "$(hostname)"
kv "cores (nproc)"   "$(nproc 2>/dev/null || echo '?')"
kv "memory"          "$(free -h 2>/dev/null | awk 'NR==2{print $2" total, "$7" available"}' || echo '?')"
kv "load average"    "$(cut -d' ' -f1-3 /proc/loadavg 2>/dev/null || echo '?')"
kv "disk free (/)"   "$(df -h / 2>/dev/null | awk 'NR==2{print $4" of "$2}')"
echo
echo "  NOTE: the load generator will run on this same box and compete for these"
echo "  cores. With <4 cores, treat any result as a floor, not a ceiling."

hdr "Containers"
docker ps --format '  {{.Names}}\t{{.Image}}\t{{.Status}}' 2>/dev/null || echo "  docker ps failed"

hdr "Fineract JVM"
HEAP=$(docker inspect "$FINERACT_CTR" 2>/dev/null \
       | grep -oE '\-Xmx[0-9]+[GgMm]' | head -1)
kv "heap flag"        "${HEAP:-not found}"
kv "container cpus"   "$(docker inspect -f '{{.HostConfig.NanoCpus}}' "$FINERACT_CTR" 2>/dev/null | awk '{print ($1==0)?"unlimited":$1/1000000000}')"
kv "container memory" "$(docker inspect -f '{{.HostConfig.Memory}}' "$FINERACT_CTR" 2>/dev/null | awk '{print ($1==0)?"unlimited":$1/1048576" MiB"}')"

hdr "Connection pools — THE concurrency ceiling"
POOL=$(docker exec "$FINERACT_DB_CTR" psql -U fineract -d fineract_tenants -At -F' ' -c "
  SELECT ts.pool_max_active, ts.pool_min_idle, ts.pool_max_idle
  FROM tenants t JOIN tenant_server_connections ts ON ts.id = t.schema_id
  WHERE t.identifier = '${TENANT}';" 2>/dev/null)
if [ -n "$POOL" ]; then
    set -- $POOL
    kv "per-tenant pool_max_active" "${1:-?}   <-- do not exceed this with concurrent writers"
    kv "per-tenant pool_min_idle"   "${2:-?}"
    kv "per-tenant pool_max_idle"   "${3:-?}"
    echo
    echo "  Concurrency ladder should stop at or below pool_max_active. Past it,"
    echo "  extra virtual users queue for a connection and latency climbs while"
    echo "  throughput stays flat — which reads like a Fineract limit but is not."
else
    kv "per-tenant pool" "COULD NOT READ — check the container name / tenant identifier"
fi
kv "tenants-store pool (env)" "${FINERACT_HIKARI_MAXIMUM_POOL_SIZE:-unset -> default 10}"

hdr "Metrics availability"
PROM_ENABLED=$(docker inspect "$FINERACT_CTR" 2>/dev/null | grep -c 'FINERACT_MANAGEMENT_PROMETHEUS_ENABLED=true')
if [ "${PROM_ENABLED:-0}" -gt 0 ]; then
    kv "Fineract prometheus export" "ENABLED"
else
    kv "Fineract prometheus export" "DISABLED (default) <-- see below"
    cat <<'EOF'

  Fineract's Prometheus export defaults to false (application.properties:357).
  Without it the test can still measure TPS from the client side, but cannot
  tell you WHY it stopped there. To turn it on, add to deploy/fineract/.env:

      FINERACT_MANAGEMENT_PROMETHEUS_ENABLED=true

  then: docker compose up -d --force-recreate fineract
  This is a restart, so do it BEFORE the baseline run, not between runs.
EOF
fi
kv "middleware actuator" "$(curl -sS -o /dev/null -w '%{http_code}' --max-time 3 http://localhost:9090/actuator/prometheus 2>/dev/null || echo unreachable)"

hdr "Postgres (Fineract)"
docker exec "$FINERACT_DB_CTR" psql -U fineract -d postgres -At -c "
  SELECT 'version: '||current_setting('server_version')
  UNION ALL SELECT 'max_connections: '||current_setting('max_connections')
  UNION ALL SELECT 'shared_buffers: '||current_setting('shared_buffers')
  UNION ALL SELECT 'work_mem: '||current_setting('work_mem')
  UNION ALL SELECT 'synchronous_commit: '||current_setting('synchronous_commit');" 2>/dev/null \
  | sed 's/^/  /' || echo "  could not query"
PGSS=$(docker exec "$FINERACT_DB_CTR" psql -U fineract -d "$TENANT_DB" -At -c \
  "SELECT count(*) FROM pg_available_extensions WHERE name='pg_stat_statements';" 2>/dev/null)
kv "pg_stat_statements available" "${PGSS:-?} (1 = yes; enable with shared_preload_libraries to see slow queries)"

hdr "Existing data (the baseline you are adding to)"
docker exec "$FINERACT_DB_CTR" psql -U fineract -d "$TENANT_DB" -At -F': ' -c "
  SELECT 'm_client', count(*) FROM m_client
  UNION ALL SELECT 'm_savings_account', count(*) FROM m_savings_account
  UNION ALL SELECT 'm_savings_account_transaction', count(*) FROM m_savings_account_transaction
  UNION ALL SELECT 'existing load-test accounts', count(*) FROM m_savings_account WHERE external_id LIKE 'lt-%';" 2>/dev/null \
  | sed 's/^/  /' || echo "  could not query"
echo
echo "  Per-deposit cost grows with an account's history: SavingsAccount"
echo "  .recalculateDailyBalances() (line 1008) walks EVERY transaction on the"
echo "  account on each posting. Accounts already carrying thousands of rows will"
echo "  post more slowly than fresh ones — which is real production behaviour,"
echo "  not a test artifact. Report the per-account transaction count with the TPS."

hdr "TLS material"
CA="$(cd "$(dirname "${BASH_SOURCE[0]}")/../fineract" && pwd)/ssl/cell-ca.crt"
if [ -f "$CA" ]; then
    kv "cell CA" "$CA"
    echo "  The load generator will verify TLS against this (SSL_CERT_FILE), not skip it."
else
    kv "cell CA" "NOT FOUND at $CA — 20-run.sh needs it"
fi

hdr "Optional API probe (needs ADMIN_PASSWORD)"
if [ -n "${ADMIN_PASSWORD:-}" ] && [ -f "$CA" ]; then
    CODE=$(curl -sS --cacert "$CA" -o /dev/null -w '%{http_code}' --max-time 5 \
        -u "${ADMIN_USER:-mifos}:${ADMIN_PASSWORD}" \
        -H "Fineract-Platform-TenantId: ${TENANT}" \
        "${FINERACT_URL:-https://localhost:8443/fineract-provider/api}/v1/offices" 2>/dev/null)
    kv "GET /v1/offices" "$CODE (2xx = credentials good, ready for stage 2)"
else
    kv "skipped" "export ADMIN_PASSWORD to probe the API"
fi

cat <<EOF

$(printf '\033[1m--- Next\033[0m')
  Report written to: $REPORT

  Paste it back before running stage 3 at scale. The two numbers that change
  the test design are pool_max_active and the core count; everything else is
  context for reading the result.

    ./10-fixtures.sh 200        # stage 2: create 200 test accounts
    ./20-run.sh                 # stage 3: smoke (1 VU, 10 requests)
    ./20-run.sh --mode full     # stage 3: the real ramp
EOF
