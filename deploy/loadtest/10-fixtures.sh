#!/usr/bin/env bash
# STAGE 2: create the savings accounts the load test posts into.
#
# WHY MANY ACCOUNTS, AND WHY THAT IS THE WHOLE POINT
# --------------------------------------------------
# SavingsAccount carries @Version (SavingsAccount.java:142), so two concurrent
# postings to the SAME account collide on optimistic locking. Fineract does not
# fail them — it RETRIES, with backoff:
#
#     fineract.retry.instances.executeCommand.max-attempts=3      (:470)
#     fineract.retry.instances.executeCommand.wait-duration=1s    (:471)
#     ...enable-exponential-backoff=true, multiplier=2            (:472-473)
#
# A collision therefore costs 1s, then 2s of SLEEPING. Point 50 virtual users at
# one account and you will measure roughly 1 TPS and conclude the core is
# unusable. That is the test, not the core. Every virtual user must own a
# distinct account, so you need at least as many accounts as peak concurrency —
# this script defaults to comfortably more.
#
# Everything created is tagged `lt-<runId>-<n>` so it is identifiable later.
# Fineract clients are not hard-deletable, which is why this is a STAGING-ONLY
# script (see the guard below) — it permanently adds rows.
#
# Idempotent and resumable: an account whose externalId already exists is
# skipped, so re-running after an interruption continues where it stopped.
#
# Usage:
#   cd ~/InnBucksMiddleware2.0/deploy/fineract
#   export ADMIN_PASSWORD='...' MW_WRITE_PASSWORD='...'
#   ../loadtest/10-fixtures.sh 200
set -euo pipefail

COUNT="${1:-200}"
OPENING_BALANCE="${OPENING_BALANCE:-1000000}"   # major units; funds withdrawals
CONCURRENCY="${FIXTURE_CONCURRENCY:-4}"
TENANT="${FINERACT_TENANT:-default}"
ADMIN_USER="${ADMIN_USER:-mifos}"
FINERACT_URL="${FINERACT_URL:-https://localhost:8443/fineract-provider/api}"
CA="${CA_CERT:-./ssl/cell-ca.crt}"
OUT="${ACCOUNTS_FILE:-/tmp/fineract-loadtest-accounts.txt}"
RUN_ID="${RUN_ID:-$(date -u +%Y%m%d%H%M)}"

log()  { printf '>> %s\n' "$*" >&2; }
fail() { printf 'FATAL: %s\n' "$*" >&2; exit 1; }

# --- Staging-only guard -----------------------------------------------------
# Structural, not a warning: this script permanently adds clients to a core
# banking database. Making it awkward to run against prod is worth the friction.
if [ "${I_KNOW_THIS_IS_STAGING:-}" != "yes" ]; then
    cat >&2 <<EOF
REFUSING TO RUN.

This creates $COUNT clients and savings accounts that CANNOT be deleted
afterwards (Fineract clients are not hard-deletable). That is fine on a staging
cell and is not fine anywhere else.

Confirm the target, then re-run with:

    I_KNOW_THIS_IS_STAGING=yes $0 $COUNT

Current target: $FINERACT_URL  (tenant: $TENANT)
Take a backup first if you have not:  ../backup-cell.sh
EOF
    exit 1
fi

[ -f "$CA" ] || fail "CA cert not found at $CA — run from deploy/fineract/, or set CA_CERT"
: "${ADMIN_PASSWORD:?export ADMIN_PASSWORD}"
: "${MW_WRITE_PASSWORD:?export MW_WRITE_PASSWORD}"
command -v jq >/dev/null || fail "jq is required"

CURL_OPTS=(--cacert "$CA")

# api METHOD PATH [BODY] [USER] [PASS]
api() {
    local method="$1" path="$2" body="${3:-}" user="${4:-$ADMIN_USER}" pass="${5:-$ADMIN_PASSWORD}"
    local args=(-sS "${CURL_OPTS[@]}" -X "$method" -u "${user}:${pass}"
                -H "Fineract-Platform-TenantId: ${TENANT}" -H "Content-Type: application/json"
                -w '\n%{http_code}')
    [ -n "$body" ] && args+=(-d "$body")
    local out status payload
    out=$(curl "${args[@]}" "${FINERACT_URL}${path}")
    status="${out##*$'\n'}"; payload="${out%$'\n'*}"
    if [ "$status" -ge 400 ]; then
        printf 'API %s %s -> %s\n%s\n' "$method" "$path" "$status" "$payload" >&2
        return 1
    fi
    printf '%s' "$payload"
}

# --- Discover the product + payment type provision-cell.sh made -------------
log "resolving savings product and payment type ..."
PRODUCT_ID=$(api GET "/v1/savingsproducts" \
    | jq -r --arg n "${SAVINGS_PRODUCT_NAME:-InnBucks Wallet}" \
        '[.. | objects | select(.name? == $n)] | .[0].id // empty')
[ -n "$PRODUCT_ID" ] || fail "savings product '${SAVINGS_PRODUCT_NAME:-InnBucks Wallet}' not found — run provision-cell.sh first"

PAYMENT_TYPE_ID=$(api GET "/v1/paymenttypes" \
    | jq -r --arg n "${PAYMENT_TYPE_NAME:-InnBucks Wallet}" \
        '[.. | objects | select(.name? == $n)] | .[0].id // empty')
[ -n "$PAYMENT_TYPE_ID" ] || fail "payment type not found — run provision-cell.sh first (Fineract requires paymentTypeId on every deposit)"
log "product=$PRODUCT_ID paymentType=$PAYMENT_TYPE_ID runId=$RUN_ID"
# RUN_ID is baked into every externalId, and it defaults to the current MINUTE.
# So resuming needs the ORIGINAL id passed back in — without it a re-run mints a
# fresh namespace, the resume check correctly finds nothing, and you end up with
# the interrupted run's partial accounts PLUS a complete second set.
log "TO RESUME THIS RUN after an interruption, re-run with:"
log "    RUN_ID=$RUN_ID I_KNOW_THIS_IS_STAGING=yes $0 $COUNT"

TODAY=$(date -u +%Y-%m-%d)

# --- Create one account -----------------------------------------------------
make_one() {
    local n="$1"
    local ext="lt-${RUN_ID}-${n}"
    local wallet="${ext}:wallet"

    # Resume: already there?
    #
    # This GET runs as ADMIN, deliberately, and must NOT be switched to the
    # write credential to "match production". innbucks-mw-write holds no
    # READ_SAVINGSACCOUNT (provision-cell.sh WRITE_PERMS — reads ride
    # innbucks-mw-read), so as the write user this call 403s on every account,
    # including ones that exist. api() treats >=400 as failure, so the check
    # answered "not there" every time and the resumability this script claims
    # did not exist: a re-run after an interruption re-attempted every account
    # and failed each one on the duplicate client externalId.
    if api GET "/v1/savingsaccounts/external-id/${wallet//:/%3A}" >/dev/null 2>&1; then
        printf '%s\n' "$wallet"
        return 0
    fi

    local client_resp client_id
    client_resp=$(api POST "/v1/clients" "$(jq -n --arg ext "$ext" --arg d "$TODAY" '{
            officeId:1, firstname:"LoadTest", lastname:$ext, externalId:$ext,
            legalFormId:1, active:true, activationDate:$d,
            locale:"en", dateFormat:"yyyy-MM-dd"
        }')" innbucks-mw-write "$MW_WRITE_PASSWORD") || return 1
    client_id=$(jq -r '.clientId // .resourceId // empty' <<<"$client_resp")
    if [ -z "$client_id" ]; then
        # A 2xx with no id is maker-checker parking the command (the transaction
        # rolls back and you get a success-SHAPED response with no row).
        printf 'MAKER-CHECKER: client create returned 2xx with no id. See the note in provision-cell.sh.\n' >&2
        return 1
    fi

    local savings_id
    savings_id=$(api POST "/v1/savingsaccounts" "$(jq -n \
            --argjson c "$client_id" --argjson p "$PRODUCT_ID" --arg ext "$wallet" --arg d "$TODAY" '{
            clientId:$c, productId:$p, externalId:$ext, submittedOnDate:$d,
            locale:"en", dateFormat:"yyyy-MM-dd"
        }')" innbucks-mw-write "$MW_WRITE_PASSWORD" | jq -r '.resourceId // .savingsId') || return 1

    api POST "/v1/savingsaccounts/${savings_id}?command=approve" \
        "$(jq -n --arg d "$TODAY" '{approvedOnDate:$d, locale:"en", dateFormat:"yyyy-MM-dd"}')" \
        innbucks-mw-write "$MW_WRITE_PASSWORD" >/dev/null || return 1
    api POST "/v1/savingsaccounts/${savings_id}?command=activate" \
        "$(jq -n --arg d "$TODAY" '{activatedOnDate:$d, locale:"en", dateFormat:"yyyy-MM-dd"}')" \
        innbucks-mw-write "$MW_WRITE_PASSWORD" >/dev/null || return 1

    # Opening balance so a withdrawal-heavy mix does not fail on insufficient
    # funds — which would measure Fineract's validation path, not its posting path.
    api POST "/v1/savingsaccounts/${savings_id}/transactions?command=deposit" \
        "$(jq -n --arg d "$TODAY" --argjson amt "$OPENING_BALANCE" --argjson pt "$PAYMENT_TYPE_ID" \
            --arg ref "${wallet}-opening" '{
            transactionDate:$d, transactionAmount:$amt, externalId:$ref, paymentTypeId:$pt,
            locale:"en", dateFormat:"yyyy-MM-dd"}')" \
        innbucks-mw-write "$MW_WRITE_PASSWORD" >/dev/null || return 1

    printf '%s\n' "$wallet"
}
export -f make_one api log fail
export FINERACT_URL TENANT ADMIN_USER ADMIN_PASSWORD MW_WRITE_PASSWORD \
       PRODUCT_ID PAYMENT_TYPE_ID TODAY RUN_ID OPENING_BALANCE CA

# --- Create them ------------------------------------------------------------
log "creating ${COUNT} accounts (concurrency ${CONCURRENCY}) -> ${OUT}"
: > "$OUT"
FAILED=0
if command -v xargs >/dev/null; then
    seq 1 "$COUNT" | CURL_OPTS="--cacert $CA" xargs -P "$CONCURRENCY" -I{} \
        bash -c 'CURL_OPTS=(--cacert "$CA"); make_one {}' >> "$OUT" || FAILED=1
else
    for n in $(seq 1 "$COUNT"); do make_one "$n" >> "$OUT" || FAILED=1; done
fi

CREATED=$(wc -l < "$OUT" | tr -d ' ')
log "created/confirmed ${CREATED} of ${COUNT} accounts"
[ "$CREATED" -gt 0 ] || fail "no accounts created — see the API errors above"
if [ "$CREATED" -lt "$COUNT" ]; then
    log "WARNING: only ${CREATED}/${COUNT}. Re-run to resume; the ones above are reusable."
fi

cat <<EOF

Accounts file: $OUT   (${CREATED} accounts, tag lt-${RUN_ID}-*)

Each virtual user in stage 3 takes a DISTINCT account from this file. Keep peak
concurrency at or below ${CREATED} — and at or below the pool_max_active that
00-discover.sh reported, whichever is smaller.

    ../loadtest/20-run.sh                  # smoke first
    ../loadtest/20-run.sh --mode full
EOF
