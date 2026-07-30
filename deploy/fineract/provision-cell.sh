#!/usr/bin/env bash
# Provision a Fineract tenant for an InnBucks cell — idempotent: safe to
# re-run; every step checks before it creates.
#
#   1. wait for Fineract
#   2. (optional) rotate the built-in admin password    ROTATE_ADMIN_PASSWORD
#   3. allow the cell currency
#   4. create the wallet savings product                -> FINERACT_SAVINGS_PRODUCT_ID
#   5. create TWO least-privilege roles (read / write) after verifying every
#      permission code exists on this build — never ALL_FUNCTIONS
#   6. create the two AppUsers the middleware rides     innbucks-mw-read / -write
#   7. (optional, RUN_SMOKE=1) drive the EXACT adapter call sequence with the
#      new write credentials: client -> wallet create/approve/activate ->
#      deposit -> read the transaction back by external id
#
# Required env:
#   ADMIN_PASSWORD        current password of ADMIN_USER (default mifos)
#   MW_READ_PASSWORD      password to set for innbucks-mw-read
#   MW_WRITE_PASSWORD     password to set for innbucks-mw-write
# Optional env:
#   FINERACT_URL          default https://localhost:8443/fineract-provider/api
#   FINERACT_TENANT       default: default
#   ADMIN_USER            default: mifos
#   ROTATE_ADMIN_PASSWORD if set, admin's password is changed to this FIRST
#   CELL_CURRENCY         default: KES
#   PRODUCT_NAME          default: InnBucks Wallet   PRODUCT_SHORT: IBWL
#   CURL_OPTS             e.g. --cacert cell-ca.crt  (avoid -k outside first-boot checks)
#   RUN_SMOKE             1 to run the probe sequence (leaves a SMOKE-Probe client behind)
#
# Deps: curl, jq.
set -euo pipefail

FINERACT_URL="${FINERACT_URL:-https://localhost:8443/fineract-provider/api}"
TENANT="${FINERACT_TENANT:-default}"
ADMIN_USER="${ADMIN_USER:-mifos}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:?export ADMIN_PASSWORD}"
MW_READ_PASSWORD="${MW_READ_PASSWORD:?export MW_READ_PASSWORD}"
MW_WRITE_PASSWORD="${MW_WRITE_PASSWORD:?export MW_WRITE_PASSWORD}"
CELL_CURRENCY="${CELL_CURRENCY:-KES}"
PRODUCT_NAME="${PRODUCT_NAME:-InnBucks Wallet}"
PRODUCT_SHORT="${PRODUCT_SHORT:-IBWL}"
CURL_OPTS="${CURL_OPTS:-}"

READ_PERMS=(READ_CLIENT READ_SAVINGSACCOUNT READ_SAVINGSACCOUNTTRANSACTION READ_ACCOUNTTRANSFER)
WRITE_PERMS=(CREATE_CLIENT CREATE_SAVINGSACCOUNT APPROVE_SAVINGSACCOUNT ACTIVATE_SAVINGSACCOUNT
             DEPOSIT_SAVINGSACCOUNT WITHDRAWAL_SAVINGSACCOUNT CREATE_ACCOUNTTRANSFER)

log()  { printf '>> %s\n' "$*" >&2; }
fail() { printf 'FATAL: %s\n' "$*" >&2; exit 1; }

# api METHOD PATH [JSON_BODY] [USER PASS] — prints the response body; nonzero on >=400.
api() {
  local method="$1" path="$2" body="${3:-}" user="${4:-$ADMIN_USER}" pass="${5:-$ADMIN_PASSWORD}"
  local args=(-sS ${CURL_OPTS} -X "$method" -u "${user}:${pass}"
              -H "Fineract-Platform-TenantId: ${TENANT}" -H "Content-Type: application/json"
              -w '\n%{http_code}')
  [[ -n "$body" ]] && args+=(-d "$body")
  local out; out=$(curl "${args[@]}" "${FINERACT_URL}${path}")
  local status="${out##*$'\n'}" payload="${out%$'\n'*}"
  if (( status >= 400 )); then
    printf 'API %s %s -> %s\n%s\n' "$method" "$path" "$status" "$payload" >&2
    return 1
  fi
  printf '%s' "$payload"
}

log "1/7 waiting for Fineract at ${FINERACT_URL} ..."
for i in $(seq 1 60); do
  if api GET "/v1/offices" >/dev/null 2>&1; then break; fi
  [[ "$i" == 60 ]] && fail "Fineract not reachable/authenticated after 5 minutes"
  sleep 5
done
log "Fineract is up."

if [[ -n "${ROTATE_ADMIN_PASSWORD:-}" ]]; then
  log "2/7 rotating '${ADMIN_USER}' password ..."
  ADMIN_ID=$(api GET "/v1/users" | jq -r --arg u "$ADMIN_USER" '.[] | select(.username==$u) | .id')
  [[ -n "$ADMIN_ID" ]] || fail "admin user ${ADMIN_USER} not found"
  api PUT "/v1/users/${ADMIN_ID}" \
    "$(jq -n --arg p "$ROTATE_ADMIN_PASSWORD" '{password:$p, repeatPassword:$p}')" >/dev/null
  ADMIN_PASSWORD="$ROTATE_ADMIN_PASSWORD"
  log "admin password rotated."
else
  log "2/7 skipping admin rotation (ROTATE_ADMIN_PASSWORD not set)."
fi

log "3/7 ensuring ${CELL_CURRENCY} is an allowed currency ..."
CURRENCIES=$(api GET "/v1/currencies")
if ! jq -e --arg c "$CELL_CURRENCY" '.selectedCurrencyOptions[]? | select(.code==$c)' \
     <<<"$CURRENCIES" >/dev/null; then
  NEW=$(jq -c --arg c "$CELL_CURRENCY" \
        '{currencies: ([.selectedCurrencyOptions[]?.code] + [$c])}' <<<"$CURRENCIES")
  api PUT "/v1/currencies" "$NEW" >/dev/null
  log "${CELL_CURRENCY} added."
else
  log "${CELL_CURRENCY} already allowed."
fi

log "4/7 ensuring savings product '${PRODUCT_NAME}' ..."
PRODUCT_ID=$(api GET "/v1/savingsproducts" \
  | jq -r --arg n "$PRODUCT_NAME" '.[] | select(.name==$n) | .id' | head -1)
if [[ -z "$PRODUCT_ID" ]]; then
  # Zero-interest wallet, accountingRule 1 = None (add Cash accounting + GL
  # later as a deliberate change). locale is mandatory on this body.
  PRODUCT_ID=$(api POST "/v1/savingsproducts" "$(jq -n \
      --arg name "$PRODUCT_NAME" --arg short "$PRODUCT_SHORT" --arg cur "$CELL_CURRENCY" '{
        name:$name, shortName:$short, description:"InnBucks customer wallet",
        currencyCode:$cur, digitsAfterDecimal:2, inMultiplesOf:0,
        nominalAnnualInterestRate:0,
        interestCompoundingPeriodType:1, interestPostingPeriodType:4,
        interestCalculationType:1, interestCalculationDaysInYearType:365,
        accountingRule:1, locale:"en"
      }')" | jq -r '.resourceId')
  log "created savings product id=${PRODUCT_ID}"
else
  log "product exists id=${PRODUCT_ID}"
fi

log "5/7 ensuring least-privilege roles ..."
AVAILABLE=$(api GET "/v1/permissions" | jq -r '.[].code')
for p in "${READ_PERMS[@]}" "${WRITE_PERMS[@]}"; do
  grep -qx "$p" <<<"$AVAILABLE" \
    || fail "permission code '$p' does not exist on this build — check: SELECT code FROM m_permission"
done

ensure_role() { # NAME DESCRIPTION PERMS...
  local name="$1" desc="$2"; shift 2
  local id
  id=$(api GET "/v1/roles" | jq -r --arg n "$name" '.[] | select(.name==$n) | .id' | head -1)
  if [[ -z "$id" ]]; then
    id=$(api POST "/v1/roles" \
      "$(jq -n --arg n "$name" --arg d "$desc" '{name:$n, description:$d}')" | jq -r '.resourceId')
    log "created role ${name} id=${id}"
  else
    log "role ${name} exists id=${id}"
  fi
  local perm_json
  perm_json=$(printf '%s\n' "$@" | jq -R . | jq -s 'map({(.): true}) | add | {permissions: .}')
  api PUT "/v1/roles/${id}/permissions" "$perm_json" >/dev/null
  log "role ${name}: permissions applied ($*)"
  printf '%s' "$id"
}

READ_ROLE_ID=$(ensure_role "innbucks-mw-read"  "InnBucks middleware read-only"  "${READ_PERMS[@]}")
WRITE_ROLE_ID=$(ensure_role "innbucks-mw-write" "InnBucks middleware commands" "${WRITE_PERMS[@]}")

log "6/7 ensuring AppUsers ..."
ensure_user() { # USERNAME PASSWORD ROLE_ID
  local username="$1" password="$2" role_id="$3"
  if api GET "/v1/users" | jq -e --arg u "$username" '.[] | select(.username==$u)' >/dev/null; then
    log "user ${username} exists (password NOT changed — rotate via the admin UI if needed)"
    return
  fi
  api POST "/v1/users" "$(jq -n \
      --arg u "$username" --arg p "$password" --argjson r "$role_id" '{
        username:$u, firstname:"InnBucks", lastname:"Middleware",
        email:"ops@innbucks.local", officeId:1, roles:[$r],
        password:$p, repeatPassword:$p, sendPasswordToEmail:false,
        passwordNeverExpires:true
      }')" >/dev/null
  log "created user ${username}"
}
ensure_user "innbucks-mw-read"  "$MW_READ_PASSWORD"  "$READ_ROLE_ID"
ensure_user "innbucks-mw-write" "$MW_WRITE_PASSWORD" "$WRITE_ROLE_ID"

if [[ "${RUN_SMOKE:-0}" == "1" ]]; then
  log "7/7 smoke: driving the adapter's exact call sequence with the mw credentials ..."
  TODAY=$(date -u +%Y-%m-%d) REF="smoke-$(date -u +%s)"
  CLIENT_EXT="smoke-probe-$(date -u +%s)"
  CLIENT_ID=$(api POST "/v1/clients" "$(jq -n --arg ext "$CLIENT_EXT" --arg d "$TODAY" '{
        officeId:1, firstname:"SMOKE", lastname:"Probe", externalId:$ext,
        legalFormId:1, active:true, activationDate:$d,
        locale:"en", dateFormat:"yyyy-MM-dd"
      }')" innbucks-mw-write "$MW_WRITE_PASSWORD" | jq -r '.clientId // .resourceId')
  SAVINGS_ID=$(api POST "/v1/savingsaccounts" "$(jq -n \
      --argjson c "$CLIENT_ID" --argjson p "$PRODUCT_ID" --arg ext "${CLIENT_EXT}:wallet" --arg d "$TODAY" '{
        clientId:$c, productId:$p, externalId:$ext, submittedOnDate:$d,
        locale:"en", dateFormat:"yyyy-MM-dd"
      }')" innbucks-mw-write "$MW_WRITE_PASSWORD" | jq -r '.resourceId // .savingsId')
  api POST "/v1/savingsaccounts/${SAVINGS_ID}?command=approve" \
    "$(jq -n --arg d "$TODAY" '{approvedOnDate:$d, locale:"en", dateFormat:"yyyy-MM-dd"}')" \
    innbucks-mw-write "$MW_WRITE_PASSWORD" >/dev/null
  api POST "/v1/savingsaccounts/${SAVINGS_ID}?command=activate" \
    "$(jq -n --arg d "$TODAY" '{activatedOnDate:$d, locale:"en", dateFormat:"yyyy-MM-dd"}')" \
    innbucks-mw-write "$MW_WRITE_PASSWORD" >/dev/null
  api POST "/v1/savingsaccounts/external-id/${CLIENT_EXT}%3Awallet/transactions?command=deposit" \
    "$(jq -n --arg d "$TODAY" --arg ref "$REF" '{
        transactionDate:$d, transactionAmount:1.00, externalId:$ref,
        locale:"en", dateFormat:"yyyy-MM-dd"}')" \
    innbucks-mw-write "$MW_WRITE_PASSWORD" >/dev/null
  # Reconciliation read with the READ credential — proves both AppUsers.
  api GET "/v1/savingsaccounts/external-id/${CLIENT_EXT}%3Awallet/transactions/external-id/${REF}" \
    "" innbucks-mw-read "$MW_READ_PASSWORD" | jq '{id, amount, reversed}' >&2
  log "smoke passed (probe client '${CLIENT_EXT}' left behind — Fineract clients aren't hard-deletable)"
else
  log "7/7 skipping smoke (set RUN_SMOKE=1 to run it)."
fi

log "DONE. Middleware .env values:"
cat >&2 <<EOF
  FINERACT_SAVINGS_PRODUCT_ID=${PRODUCT_ID}
  FINERACT_CURRENCY=${CELL_CURRENCY}
  FINERACT_OFFICE_ID=1
  FINERACT_READ_USERNAME=innbucks-mw-read
  FINERACT_WRITE_USERNAME=innbucks-mw-write
  (passwords: the values you provided in MW_READ_PASSWORD / MW_WRITE_PASSWORD)
EOF
