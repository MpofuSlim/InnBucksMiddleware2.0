#!/usr/bin/env bash
# Provision a Fineract tenant for an InnBucks cell — idempotent: safe to
# re-run; every step checks before it creates.
#
#   1. wait for Fineract
#   2. (optional) rotate the built-in admin password    ROTATE_ADMIN_PASSWORD
#   3. allow the cell currency
#   4. create the wallet savings product                -> FINERACT_SAVINGS_PRODUCT_ID
#   4b. create the payment type for wallet movements    -> FINERACT_PAYMENT_TYPE_ID
#   4c. (optional) register the core-event web hook      CORE_EVENTS_TOKEN
#   4d. seed the client Constitution / Main Business Line code values (the CBS
#       console's Entity-client dropdowns)              CLIENT_CONSTITUTIONS etc
#   5. create TWO least-privilege roles (read / write) after verifying every
#      permission code exists on this build — never ALL_FUNCTIONS
#   6. create the two AppUsers the middleware rides     innbucks-mw-read / -write
#   6b. grant the BANK's operational roles                BANK_ROLE_GRANTS
#   6c. make GL control accounts refuse manual journals   CONTROL_ACCOUNT_GLCODES
#   6d. warn on loan products with accounting = None      ASSERT_LOAN_PRODUCT_ACCOUNTING
#   6e. maker-checker: flag tasks, ASSERT the middleware's own codes are NOT
#       flagged (always, not configurable), then optionally flip the global
#       switch                        MAKER_CHECKER_TASKS / ENABLE_MAKER_CHECKER
#   7. (optional, RUN_SMOKE=1) drive the EXACT adapter call sequence with the
#      new write credentials: client -> wallet create/approve/activate ->
#      deposit -> read the transaction back by external id
#
# Steps 6b-6e are per-market POLICY and belong in a cell file, not in your
# shell history:
#
#     set -a; source deploy/cells/cell.zw.env; set +a     # policy, committed
#     set -a; source deploy/fineract/.env;     set +a     # secrets, gitignored
#     ./deploy/fineract/provision-cell.sh
#
# See deploy/cells/cell.example.env. Everything is additive and idempotent: a
# re-run re-asserts the file's intent and never revokes a grant it doesn't name.
#
# Required env:
#   ADMIN_PASSWORD        current password of ADMIN_USER (default mifos)
#   MW_READ_PASSWORD      password to set for innbucks-mw-read
#   MW_WRITE_PASSWORD     password to set for innbucks-mw-write
#   CELL_CURRENCY         ISO-4217 code for this cell, e.g. USD (no default)
# Optional env:
#   FINERACT_URL          default https://localhost:8443/fineract-provider/api
#   FINERACT_TENANT       default: default
#   ADMIN_USER            default: mifos
#   ROTATE_ADMIN_PASSWORD if set, admin's password is changed to this FIRST
#                         (on a re-run, also accepted as the CURRENT password)
#   PRODUCT_NAME          default: InnBucks Wallet   PRODUCT_SHORT: IBWL
#   PAYMENT_TYPE_NAME     default: InnBucks Wallet
#   CLIENT_CONSTITUTIONS  '|'-separated Constitution values (default: ZW set)
#   CLIENT_BUSINESS_LINES '|'-separated Main Business Line values (default set)
#                         Set either to '' to skip seeding that code.
#   CURL_OPTS             e.g. --cacert cell-ca.crt  (avoid -k outside first-boot checks)
#   RUN_SMOKE             1 to run the probe sequence (leaves a SMOKE-Probe client behind)
#
# Deps: curl, jq. (grep -P is used for the password-policy pre-check; without
# it the check is skipped, not fatal.)
#
# Generate a policy-compliant password:  ./provision-cell.sh --gen-password
set -euo pipefail

# Fineract's ACTIVE password policy, keyed by the `key` the API reports.
# Verbatim from the fork's Liquibase seed (0002_initial_data.xml and
# 0152_update_password_validation_policy.xml) — the API exposes the policy's
# key and description but NOT its regex, so we carry the regexes here.
policy_regex() {
  case "$1" in
    simple) printf '%s' '^.{1,50}$' ;;
    secure) printf '%s' '^(?=.*\d)(?=.*[a-z])(?=.*[A-Z])(?!.*\s).{6,50}$' ;;
    strong) printf '%s' '^(?!.*(.)\1)(?!.*\s)(?=.*\d)(?=.*[a-z])(?=.*[A-Z])(?=.*[^\w\s]).{12,50}$' ;;
    *)      printf '' ;;
  esac
}

# `strong` (the default on a current build) forbids CONSECUTIVE REPEATED
# characters, which openssl rand -base64 24 violates roughly 40% of the time.
# Reject-and-retry until we get one that passes.
gen_password() {
  local candidate re; re=$(policy_regex strong)
  while :; do
    # head FIRST, then filter: piping /dev/urandom into `head -c` kills the
    # upstream with SIGPIPE, which pipefail turns into a fatal 141.
    candidate=$(LC_ALL=C head -c 512 /dev/urandom | tr -dc 'A-Za-z0-9@=.+-' | cut -c1-20)
    if printf '%s' "$candidate" | grep -qP -- "$re"; then
      printf '%s\n' "$candidate"; return
    fi
  done
}

if [[ "${1:-}" == "--gen-password" ]]; then gen_password; exit 0; fi

FINERACT_URL="${FINERACT_URL:-https://localhost:8443/fineract-provider/api}"
TENANT="${FINERACT_TENANT:-default}"
ADMIN_USER="${ADMIN_USER:-mifos}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:?export ADMIN_PASSWORD}"
MW_READ_PASSWORD="${MW_READ_PASSWORD:?export MW_READ_PASSWORD}"
MW_WRITE_PASSWORD="${MW_WRITE_PASSWORD:?export MW_WRITE_PASSWORD}"
# No default on purpose: the savings product is created in this currency and
# a cell that silently comes up in the wrong one is expensive to unpick.
CELL_CURRENCY="${CELL_CURRENCY:?export CELL_CURRENCY (ISO-4217, e.g. USD)}"
PRODUCT_NAME="${PRODUCT_NAME:-InnBucks Wallet}"
PRODUCT_SHORT="${PRODUCT_SHORT:-IBWL}"
PAYMENT_TYPE_NAME="${PAYMENT_TYPE_NAME:-InnBucks Wallet}"
CURL_OPTS="${CURL_OPTS:-}"

# Every code below is checked against the running build before the roles are
# written (step 5). Notes on the two non-obvious ones:
#   * There is NO READ_SAVINGSACCOUNTTRANSACTION permission — the transaction
#     endpoints validate READ against the SAVINGSACCOUNT resource, so
#     READ_SAVINGSACCOUNT already covers the reconciliation read-back.
#   * ACTIVATE_CLIENT is required because the adapter creates clients with
#     active:true, and Fineract's createClient runs the activate command
#     inline (ClientWritePlatformServiceJpaRepositoryImpl ->
#     validateRollbackCommand(activateClient) -> validateHasPermissionTo).
READ_PERMS=(READ_CLIENT READ_SAVINGSACCOUNT READ_ACCOUNTTRANSFER)
WRITE_PERMS=(CREATE_CLIENT ACTIVATE_CLIENT CREATE_SAVINGSACCOUNT APPROVE_SAVINGSACCOUNT
             ACTIVATE_SAVINGSACCOUNT DEPOSIT_SAVINGSACCOUNT WITHDRAWAL_SAVINGSACCOUNT
             CREATE_ACCOUNTTRANSFER)

log()  { printf '>> %s\n' "$*" >&2; }
fail() { printf 'FATAL: %s\n' "$*" >&2; exit 1; }

# Parsing + set arithmetic for steps 6b/6e. Kept in a lib so
# provision-selftest.sh can cover it without a cell — mc_violations in
# particular is the one function here whose false negative costs money.
# shellcheck source=provision-lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/provision-lib.sh"

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

# probe USER PASS — prints the HTTP status, or 000 when the connection never
# completed. Deliberately separate from api(): the whole point of step 1 is to
# tell "not listening yet" (wait) apart from "listening and rejecting these
# credentials" (fail NOW — a wrong password will never self-heal, and the old
# retry loop burnt five minutes before saying something misleading).
probe() {
  curl -sS ${CURL_OPTS} -o /dev/null -w '%{http_code}' \
       -u "${1}:${2}" -H "Fineract-Platform-TenantId: ${TENANT}" \
       "${FINERACT_URL}/v1/offices" 2>/dev/null || true
}

log "1/7 waiting for Fineract at ${FINERACT_URL} ..."
ROTATION_DONE=0
DEADLINE=$((SECONDS + 300))
while :; do
  CODE=$(probe "$ADMIN_USER" "$ADMIN_PASSWORD")
  case "$CODE" in
    2*) break ;;
    401|403)
      # A re-run against an already-provisioned cell: step 2 rotated the
      # password on the previous pass, so ADMIN_PASSWORD is now stale.
      # Recognise that instead of dying — this script is meant to be re-runnable.
      if [[ -n "${ROTATE_ADMIN_PASSWORD:-}" && "$(probe "$ADMIN_USER" "$ROTATE_ADMIN_PASSWORD")" == 2* ]]; then
        log "admin password is ALREADY rotated (re-run) — continuing with the rotated value."
        ADMIN_PASSWORD="$ROTATE_ADMIN_PASSWORD"
        ROTATION_DONE=1
        break
      fi
      fail "Fineract is UP but rejected user '${ADMIN_USER}' (HTTP ${CODE}).
       This is a credentials problem, not a startup delay — waiting will not fix it.
       Check ADMIN_USER / ADMIN_PASSWORD (and ROTATE_ADMIN_PASSWORD if this cell
       was provisioned before). Reproduce with:
         curl -i ${CURL_OPTS} -u '${ADMIN_USER}:<password>' \\
           -H 'Fineract-Platform-TenantId: ${TENANT}' ${FINERACT_URL}/v1/offices"
      ;;
    000) : ;;  # not listening / TLS handshake incomplete — still booting
    *)   log "  unexpected status ${CODE}, retrying ..." ;;
  esac
  (( SECONDS < DEADLINE )) || fail "Fineract not reachable at ${FINERACT_URL} after 5 minutes (last status: ${CODE}).
       Check the container is healthy and that CURL_OPTS trusts its certificate:
         docker compose ps && docker compose logs --tail=50 fineract"
  sleep 5
done
log "Fineract is up."

# Validate every password we are about to SET against the cell's active
# policy, before the first write. Otherwise the run half-applies and dies on
# a 400 whose body doesn't say which value was rejected.
# GET /v1/passwordpreferences returns the ACTIVE policy as a single object
# (retrieveActiveValidationPolicy), not a list — /template returns the list.
# Tolerate both so a future shape change degrades to "skipped", not a jq error.
POLICY_KEY=$(api GET "/v1/passwordpreferences" 2>/dev/null \
  | jq -r '(if type == "array" then (map(select(.active)) | .[0].key?) else .key end) // empty' || true)
POLICY_RE=$(policy_regex "${POLICY_KEY:-}")
if [[ -z "$POLICY_KEY" ]]; then
  log "  (could not read the active password policy — skipping the pre-check)"
elif [[ -z "$POLICY_RE" ]]; then
  log "  (unknown password policy '${POLICY_KEY}' — skipping the pre-check)"
elif ! printf 'x' | grep -qP 'x' 2>/dev/null; then
  log "  (grep -P unavailable — skipping the password-policy pre-check)"
else
  for var in ROTATE_ADMIN_PASSWORD MW_READ_PASSWORD MW_WRITE_PASSWORD; do
    value="${!var:-}"
    [[ -n "$value" ]] || continue
    printf '%s' "$value" | grep -qP -- "$POLICY_RE" || fail \
      "${var} does not satisfy this cell's '${POLICY_KEY}' password policy.
       Note '${POLICY_KEY}' forbids CONSECUTIVE REPEATED characters, which is why
       openssl rand -base64 24 fails here often. Generate a compliant one with:
         export ${var}=\"\$(./provision-cell.sh --gen-password)\""
  done
  log "passwords satisfy the '${POLICY_KEY}' policy."
fi

# Fineract stamps writes with its LOGICAL BUSINESS DATE, not the wall clock.
# When that feature is on and its date is stale — the normal state of a cell
# restored from an older dump, since nothing advanced it while the stack was
# down — every write dated today is rejected as "in the future", and the error
# names the date you SENT, never the one it compared against. Catch it here
# rather than letting the operator debug it from the smoke step.
TODAY_UTC=$(date -u +%Y-%m-%d)
TODAY_NUM=$(date -u +%Y%m%d)

# The key is KEBAB-case: migration 0149_update_global_configuration_names
# renamed every global-configuration key (UPDATE c_configuration SET name =
# REPLACE(REPLACE(LOWER(name), '_', '-'), ' ', '-')). Querying the old
# enable_business_date matches nothing and reads exactly like "the feature is
# off" — which cost a full debugging round. Both spellings are accepted so a
# cell predating 0149 still resolves.
#
# TRI-STATE on purpose: an earlier version collapsed "could not read the flag"
# into "disabled" and waved through the very cell it was written for. An
# unreadable flag stays UNKNOWN and is treated as dangerous, not as good news.
BD_FLAG=$(api GET "/v1/configurations" 2>/dev/null | jq -r '
  [.. | objects | select(.name? == "enable-business-date" or .name? == "enable_business_date")] as $c
  | if ($c | length) > 0 then ($c[0].enabled | tostring) else "unknown" end' || printf 'unknown')

# The business-date rows read reliably (LocalDate serializes as [yyyy,m,d] via
# @JsonLocalDateArrayFormat; both shapes normalised to a comparable integer).
BD_NUM=$(api GET "/v1/businessdate" 2>/dev/null | jq -r '
  [.. | objects | select(.type? == "BUSINESS_DATE")] | .[0].date // empty
  | if type == "array" then (.[0]*10000 + .[1]*100 + .[2]) else (gsub("-"; "") | tonumber) end' || true)

BD_SQL="docker compose exec -T fineract-db psql -U fineract -d ${TENANT_DB:-fineract_default}"
if [[ -n "$BD_NUM" && "$BD_NUM" != null ]] && (( BD_NUM < TODAY_NUM )); then
  case "$BD_FLAG" in
    false)
      log "business date row is stale (${BD_NUM}) but the feature reads as off — ignored."
      ;;
    *)
      [[ "$BD_FLAG" == unknown ]] \
        && WHY="could not read enable-business-date over the API, so this is treated as ON" \
        || WHY="enable-business-date is ON"
      fail "Fineract's BUSINESS_DATE is ${BD_NUM} but today (UTC) is ${TODAY_NUM}, and ${WHY}.
       Every write dated today is rejected as 'in the future' until this is fixed.
       Confirm the flag against the database (the API reads unreliably here):
         ${BD_SQL} \\
           -c \"SELECT id, name, enabled FROM c_configuration WHERE name = 'enable-business-date';\"
       For a savings-only wallet cell the fix is to turn the feature OFF — Fineract
       then uses the tenant's own date and nothing has to advance it daily:
         ${BD_SQL} \\
           -c \"UPDATE c_configuration SET enabled = false WHERE name = 'enable-business-date';\"
         docker compose restart fineract
       The restart is REQUIRED: the flag is cached (@Cacheable(\"configByName\")) and
       only evicted on the API update path, so a direct SQL write is invisible until
       the process restarts.
       If this cell deliberately runs on business dates, advance it instead — and make
       sure something keeps advancing it, or this recurs tomorrow:
         curl -sS ${CURL_OPTS} -X POST -u '${ADMIN_USER}:<password>' \\
           -H 'Fineract-Platform-TenantId: ${TENANT}' -H 'Content-Type: application/json' \\
           -d '{\"type\":\"BUSINESS_DATE\",\"date\":\"${TODAY_UTC}\",\"locale\":\"en\",\"dateFormat\":\"yyyy-MM-dd\"}' \\
           ${FINERACT_URL}/v1/businessdate"
      ;;
  esac
elif [[ "$BD_FLAG" == unknown ]]; then
  log "could not read enable-business-date over the API; BUSINESS_DATE row looks current."
else
  log "business date: feature=${BD_FLAG}, row current."
fi

if [[ -n "${ROTATE_ADMIN_PASSWORD:-}" && "$ROTATION_DONE" == 0 ]]; then
  log "2/7 rotating '${ADMIN_USER}' password ..."
  ADMIN_ID=$(api GET "/v1/users" | jq -r --arg u "$ADMIN_USER" '.[] | select(.username==$u) | .id')
  [[ -n "$ADMIN_ID" ]] || fail "admin user ${ADMIN_USER} not found"
  api PUT "/v1/users/${ADMIN_ID}" \
    "$(jq -n --arg p "$ROTATE_ADMIN_PASSWORD" '{password:$p, repeatPassword:$p}')" >/dev/null
  ADMIN_PASSWORD="$ROTATE_ADMIN_PASSWORD"
  log "admin password rotated."
elif [[ "$ROTATION_DONE" == 1 ]]; then
  log "2/7 admin rotation already done on a previous run."
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

log "4b/7 ensuring payment type '${PAYMENT_TYPE_NAME}' ..."
# Fineract validates paymentTypeId as notNull() on EVERY savings deposit and
# withdrawal (SavingsAccountTransactionDataValidator.validate) — a cell without
# one 400s every money movement the middleware attempts, not just the smoke.
PAYMENT_TYPE_ID=$(api GET "/v1/paymenttypes" \
  | jq -r --arg n "$PAYMENT_TYPE_NAME" '[.. | objects | select(.name? == $n)] | .[0].id // empty')
if [[ -z "$PAYMENT_TYPE_ID" ]]; then
  PAYMENT_TYPE_ID=$(api POST "/v1/paymenttypes" "$(jq -n --arg n "$PAYMENT_TYPE_NAME" '{
        name:$n, description:"InnBucks middleware wallet movements",
        isCashPayment:false, position:1
      }')" | jq -r '[.. | objects | select(.resourceId? != null)] | .[0].resourceId // empty')
  [[ -n "$PAYMENT_TYPE_ID" ]] || fail "could not create payment type '${PAYMENT_TYPE_NAME}' — check CREATE_PAYMENTTYPE permission"
  log "created payment type id=${PAYMENT_TYPE_ID}"
else
  log "payment type exists id=${PAYMENT_TYPE_ID}"
fi

# 4c. Web hook -> middleware, so a teller/admin posting still SMSes the
# customer. Registered only when CORE_EVENTS_TOKEN is provided; idempotent by
# hook name (delete-and-recreate, so URL/token rotation is just a re-run).
# The token IS the auth — Fineract's Web hook template cannot set a header —
# so it rides the URL path, over the private cell network only.
if [[ -n "${CORE_EVENTS_TOKEN:-}" ]]; then
  # The trailing slash is LOAD-BEARING: Fineract hands this URL to Retrofit,
  # which rejects a slash-less base URL outright ("baseUrl must end in /" —
  # the hook create 500s), and then POSTs to the slashed form, which the
  # middleware's controller maps explicitly.
  MIDDLEWARE_HOOK_URL="${MIDDLEWARE_INTERNAL_URL:-http://innbucks-middleware:8090}/internal/core-events/fineract/${CORE_EVENTS_TOKEN}/"
  log "4c/7 registering core-event web hook ..."
  WEB_TEMPLATE_ID=$(api GET "/v1/hooks/template" \
    | jq -r '[.templates[]? // empty | select(.name == "Web")] | .[0].id // empty')
  [[ -n "$WEB_TEMPLATE_ID" ]] || WEB_TEMPLATE_ID=$(api GET "/v1/hooks/template" \
    | jq -r '[.. | objects | select(.name? == "Web")] | .[0].id // empty')
  [[ -n "$WEB_TEMPLATE_ID" ]] || fail "no 'Web' hook template on this build — cannot register the core-event hook"
  # createHook stores displayName AS the hook's name, so match either field.
  EXISTING_HOOK_ID=$(api GET "/v1/hooks" \
    | jq -r '[.[]? | select((.displayName? == "innbucks-middleware-core-events")
                        or (.name? == "innbucks-middleware-core-events"))] | .[0].id // empty')
  if [[ -n "$EXISTING_HOOK_ID" ]]; then
    # Recreate rather than diff: the URL embeds the token, and a stale token
    # is precisely the misconfiguration a re-run must fix.
    api DELETE "/v1/hooks/${EXISTING_HOOK_ID}" >/dev/null || true
  fi
  api POST "/v1/hooks" "$(jq -n --argjson t "$WEB_TEMPLATE_ID" --arg url "$MIDDLEWARE_HOOK_URL" '{
        name:"Web", displayName:"innbucks-middleware-core-events", isActive:true, templateId:$t,
        config:{"Payload URL":$url, "Content Type":"json"},
        events:[{entityName:"SAVINGSACCOUNT", actionName:"DEPOSIT"},
                {entityName:"SAVINGSACCOUNT", actionName:"WITHDRAWAL"}]
      }')" >/dev/null || fail "hook registration failed — check the payload against this build's /v1/hooks API"
  log "core-event hook registered -> ${MIDDLEWARE_HOOK_URL%/*}/<token>"
else
  log "4c/7 CORE_EVENTS_TOKEN not set — skipping the core-event web hook (teller/admin postings will not SMS customers)"
fi

# 4d. Client Constitution / Main Business Line code values — the dropdowns the
# CBS console's Entity ("company") client form needs. Fineract ships both CODES
# but ZERO values, and it requires constitutionId whenever a client's
# clientNonPersonDetails block is present (ClientNonPerson's constructor always
# runs validate()), so on an unseeded cell there is no id the console can
# legally submit and company details cannot be recorded AT ALL. Verified on the
# ZW cell 2026-09-02; full contract in docs/client-legal-form-api.md.
#
# Deliberately NON-FATAL, unlike the steps above. The middleware creates only
# PERSON clients, so nothing it does depends on this, and a back-office form
# section must not be able to fail a cell stand-up. It is loud rather than
# fatal — the console renders that section disabled with the fix named, and
# re-running this script IS the fix.
#
# The DEFAULT list is ZIMBABWE-shaped ("Private Business Corporation" is a COBE
# Act entity type). Override it per market: these become
# m_client_non_person.constitution_cv_id foreign keys, so renaming or dropping
# one once real companies reference it is a migration, not an edit. Seeding is
# additive — values already on the cell are left exactly as they are.
CLIENT_CONSTITUTIONS="${CLIENT_CONSTITUTIONS-Sole Trader|Partnership|Private Business Corporation|Private Limited Company|Public Limited Company|Co-operative Society|Trust|Non-Governmental Organisation}"
CLIENT_BUSINESS_LINES="${CLIENT_BUSINESS_LINES-Agriculture|Mining|Manufacturing|Construction|Retail Trade|Wholesale Trade|Transport and Logistics|Hospitality and Tourism|Financial Services|Professional Services|Education|Health|Information and Communication Technology|Other}"

seed_code_values() { # CODE_NAME PIPE_SEPARATED_VALUES
  local code_name="$1" values="$2"
  local code_id existing resp v created=0 present=0 pos=0
  [[ -n "$values" ]] || { log "  ${code_name}: no values configured — skipped"; return 0; }
  code_id=$(api GET "/v1/codes" | jq -r --arg n "$code_name" \
    '[.[]? | select(.name? == $n)] | .[0].id // empty') || return 1
  if [[ -z "$code_id" ]]; then
    log "  WARN: no code named '${code_name}' on this build — skipped"
    return 0
  fi
  existing=$(api GET "/v1/codes/${code_id}/codevalues" | jq -r '.[]?.name // empty') || return 1
  while IFS= read -r v; do
    [[ -n "$v" ]] || continue
    pos=$((pos + 1))
    if grep -qxF -- "$v" <<<"$existing"; then present=$((present + 1)); continue; fi
    resp=$(api POST "/v1/codes/${code_id}/codevalues" \
      "$(jq -n --arg n "$v" --argjson p "$pos" '{name:$n, position:$p, isActive:true}')") || {
        log "  WARN: '${code_name}' value '${v}' was rejected — check the CREATE_CODEVALUE permission"
        continue
      }
    # A 2xx with no resourceId is maker-checker parking the command and rolling
    # it back — success-SHAPED, but nothing was written. Same trap as the smoke
    # step's client create.
    if [[ -z "$(jq -r '.resourceId // empty' <<<"$resp")" ]]; then
      log "  WARN: '${v}' returned 2xx with no resourceId — maker-checker parked it, nothing written"
      continue
    fi
    created=$((created + 1))
  done < <(tr '|' '\n' <<<"$values")
  log "  ${code_name} (code id=${code_id}): ${created} created, ${present} already present"
}

log "4d/7 seeding client Constitution / Main Business Line code values ..."
log "  (defaults are ZIMBABWE-shaped — override per market with CLIENT_CONSTITUTIONS / CLIENT_BUSINESS_LINES)"
seed_code_values "Constitution" "$CLIENT_CONSTITUTIONS" \
  || log "  WARN: Constitution seeding failed — the console's Entity company-details section stays unavailable"
seed_code_values "Main Business Line" "$CLIENT_BUSINESS_LINES" \
  || log "  WARN: Main Business Line seeding failed (that field is optional — Entity clients still work)"

log "5/7 ensuring least-privilege roles ..."
AVAILABLE=$(api GET "/v1/permissions" | jq -r '.[].code')
for p in "${READ_PERMS[@]}" "${WRITE_PERMS[@]}"; do
  if ! grep -qx "$p" <<<"$AVAILABLE"; then
    # Show what this build DOES offer for the same entity — a missing code is
    # nearly always a near-miss on the entity's real name.
    NEAR=$(grep -i "${p#*_}" <<<"$AVAILABLE" | head -12 | paste -sd' ' -)
    fail "permission code '$p' does not exist on this build.
       Codes on this build mentioning '${p#*_}': ${NEAR:-<none>}
       Full list: curl ${CURL_OPTS} -u '<admin>' -H 'Fineract-Platform-TenantId: ${TENANT}' \\
         ${FINERACT_URL}/v1/permissions | jq -r '.[].code'"
  fi
done

ensure_role() { # NAME DESCRIPTION PERMS...
  local name="$1" desc="$2"; shift 2
  local id
  # first(...) inside jq rather than '| head -1': head closing the pipe early
  # SIGPIPEs jq into 141, which pipefail turns fatal. Harmless while a name
  # matches at most once, lethal the day one matches twice.
  id=$(api GET "/v1/roles" | jq -r --arg n "$name" 'first(.[] | select(.name==$n) | .id) // empty')
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

# ─────────────────────────────────────────────────────────────────────────────
# 6b–6e: the BANK's operational policy, driven from deploy/cells/cell.<iso>.env.
#
# Everything below is per-market POLICY and lives in that file as data, with one
# deliberate exception: step 6e's assertion that the middleware's own permission
# codes are never maker-checkerable is derived from READ_PERMS/WRITE_PERMS above
# and is NOT configurable. A cell file can widen what the bank dual-controls; it
# can never switch off the thing that keeps the customer rail moving.
# ─────────────────────────────────────────────────────────────────────────────

# Cache the build's permission codes once — 6b and 6e both check against it.
#
# Read via a ROLE's permission listing, NOT GET /v1/permissions. The three
# permission queries in PermissionReadPlatformServiceImpl filter differently,
# and only this one is unfiltered:
#
#   GET /v1/permissions            "where code not like '%\_CHECKER'"   (:96)
#   ?makerCheckerable=true         ...also excludes 'special' and READ_%  (:108)
#   GET /v1/roles/{id}/permissions  no where clause at all               (:113)
#
# 249 of the 834 seeded permissions are '<CODE>_CHECKER' rows, so the obvious
# endpoint hides every one of them. On the ZW cell that made 6b report
# UNDOTRANSACTION_SAVINGSACCOUNT_CHECKER as "does not exist on this build" and
# skip the grant — when it exists, seeded at id 370. A checker grant is exactly
# what a maker-checker setup needs, so validating against the filtered list
# makes the step unable to do the one job the bank most needs from it.
#
# Any role id works; the query returns every permission with a 'selected' flag
# saying whether THIS role holds it. We only read the codes.
ALL_CODES=$(api GET "/v1/roles/${READ_ROLE_ID}/permissions" \
  | jq -r '.permissionUsageData[]?.code // empty')

log "6b/7 applying bank operational role grants ..."
if [[ -n "${BANK_ROLE_GRANTS:-}" ]]; then
  ALL_ROLES=$(api GET "/v1/roles")
  ALL_ROLE_NAMES=$(jq -r '.[]?.name // empty' <<<"$ALL_ROLES")
  while IFS=$'\t' read -r role_name role_codes; do
    [[ -n "$role_name" ]] || continue
    # The role must ALREADY EXIST. A grant names a role this bank's operators
    # are assigned to; if the name does not match one, the right answer is to
    # say so, never to create it.
    #
    # Creating it is what this step used to do, and it is the worst kind of
    # wrong: on the ZW cell 'Test Internal Auditor' did not match the real
    # 'TEST Internal Audit', so the run made an EMPTY role nobody holds,
    # granted it correctly, and printed "1 code(s) asserted". A success line
    # for a change that reached no human — while the auditor it was meant for
    # still could not read the audit log.
    role_id=$(jq -r --arg n "$role_name" 'first(.[] | select(.name == $n) | .id) // empty' <<<"$ALL_ROLES")
    if [[ -z "$role_id" ]]; then
      near=$(suggest_roles "$role_name" "$ALL_ROLE_NAMES") || true
      log "  !! role '${role_name}' does not exist on this cell — NOT created, grants SKIPPED."
      log "  !!   closest existing: ${near:-<none>}"
      log "  !!   fix the name in the cell file; roles are created by the bank, not here."
      continue
    fi
    # Validate BEFORE granting. An unknown code is not refused by Fineract in a
    # way that reaches the operator — the role simply never gets it, and the
    # holder authenticates fine and is refused everywhere. Warn per code and
    # grant the rest rather than failing the whole cell over one typo.
    valid=()
    while IFS= read -r c; do
      if grep -qxF -- "$c" <<<"$ALL_CODES"; then
        valid+=("$c")
      else
        # The '|| true' is load-bearing under 'set -euo pipefail'. This pipeline
        # returns nonzero in two ORDINARY situations — grep matching nothing, and
        # head closing early and SIGPIPE-ing grep into 141 (the same trap
        # gen_password documents above) — and either would kill the script
        # SILENTLY, before the warning below that explains why. Losing the hint
        # is cosmetic; exiting here skips steps 6c-6e including the
        # maker-checker assertion, which is the one thing that must always run.
        near=$(suggest_codes "$c" "$ALL_CODES") || true
        log "  WARN: '${c}' does not exist on this build — NOT granted to '${role_name}'. Similar: ${near:-<none>}"
      fi
    done < <(split_list ',' "$role_codes")
    if ((${#valid[@]} == 0)); then
      log "  '${role_name}': no valid codes — skipped"
      continue
    fi
    # PUT /v1/roles/{id}/permissions MERGES (RoleWritePlatformServiceJpaRepositoryImpl
    # iterates only the codes in the request), so this adds without stripping
    # anything the bank granted in the console.
    api PUT "/v1/roles/${role_id}/permissions" \
      "$(printf '%s\n' "${valid[@]}" | jq -R . | jq -s 'map({(.): true}) | add | {permissions: .}')" >/dev/null
    log "  '${role_name}' (id=${role_id}): ${#valid[@]} code(s) asserted"
  done < <(parse_role_grants "${BANK_ROLE_GRANTS}")
else
  log "  BANK_ROLE_GRANTS not set — skipping (roles stay as the console left them)"
fi

log "6c/7 enforcing GL control accounts (no manual journal entries) ..."
if [[ -n "${CONTROL_ACCOUNT_GLCODES:-}" ]]; then
  GL_ACCOUNTS=$(api GET "/v1/glaccounts")
  while IFS= read -r glcode; do
    glcode="${glcode// /}"
    [[ -n "$glcode" ]] || continue
    gl_row=$(jq -r --arg g "$glcode" \
      '[.[]? | select(.glCode == $g)] | .[0] | select(.!= null) | "\(.id)\t\(.manualEntriesAllowed)"' \
      <<<"$GL_ACCOUNTS")
    if [[ -z "$gl_row" ]]; then
      log "  WARN: no GL account with glCode '${glcode}' — skipped"
      continue
    fi
    gl_id="${gl_row%%$'\t'*}"; gl_manual="${gl_row##*$'\t'}"
    if [[ "$gl_manual" == "false" ]]; then
      log "  ${glcode} already refuses manual entries"
      continue
    fi
    if api PUT "/v1/glaccounts/${gl_id}" '{"manualEntriesAllowed":false}' >/dev/null; then
      log "  ${glcode} (id=${gl_id}) now refuses manual entries"
    else
      log "  WARN: could not update glCode '${glcode}' — check the UPDATE_GLACCOUNT permission"
    fi
  done < <(tr '|' '\n' <<<"${CONTROL_ACCOUNT_GLCODES}")
else
  log "  CONTROL_ACCOUNT_GLCODES not set — skipping"
fi

log "6d/7 checking loan products have accounting enabled ..."
if [[ "${ASSERT_LOAN_PRODUCT_ACCOUNTING:-1}" == "1" ]]; then
  # accountingRule id 1 = None. A write-off on such a product SUCCEEDS and posts
  # NO journal entries at all (the journal poster early-returns when no
  # accounting flag is set on the product) — silent, and only visible when the
  # GL is reconciled. Warn rather than fail: the mappings are authored in the
  # console (6-8 account ids per product) and a cell stand-up must not depend on
  # the bank's product catalogue being finished.
  UNMAPPED=$(api GET "/v1/loanproducts" \
    | jq -r '.[]? | select((.accountingRule.id // 1) == 1) | "\(.id)\t\(.name)"')
  if [[ -n "$UNMAPPED" ]]; then
    log "  WARN: these loan products have accounting = None. Write-offs on them will"
    log "        post NOTHING to the GL, silently. Set Cash/Accrual + full mappings"
    log "        (incl. losses-written-off and income-from-recovery) in the console:"
    while IFS= read -r row; do [[ -n "$row" ]] && log "          id=${row%%$'\t'*}  ${row##*$'\t'}"; done <<<"$UNMAPPED"
  else
    log "  all loan products have accounting enabled"
  fi
else
  log "  ASSERT_LOAN_PRODUCT_ACCOUNTING=0 — skipped"
fi

log "6e/7 maker-checker ..."
# Order is the whole safety argument: flag the bank's tasks, THEN assert the
# middleware's codes are clear, and only then flip the global switch.
if [[ -n "${MAKER_CHECKER_TASKS:-}" ]]; then
  mc_valid=()
  while IFS= read -r c; do
    if grep -qxF -- "$c" <<<"$ALL_CODES"; then
      mc_valid+=("$c")
    else
      log "  WARN: task '${c}' does not exist on this build — not flagged"
    fi
  done < <(split_list ',' "${MAKER_CHECKER_TASKS}")
  if ((${#mc_valid[@]})); then
    api PUT "/v1/permissions" "$(json_flag_map true "${mc_valid[@]}")" >/dev/null
    log "  flagged ${#mc_valid[@]} task(s) as maker-checkerable"
  fi
  # Flagging a task and granting its checker are two halves of ONE decision,
  # split across two settings in this file. Doing one without the other parks
  # commands into an inbox no non-superuser can act on — no error, just an
  # approval queue that never drains.
  mapfile -t MC_GAPS < <(checker_gaps "${MAKER_CHECKER_TASKS}" "${BANK_ROLE_GRANTS:-}")
  if ((${#MC_GAPS[@]})); then
    log "  WARN: ${#MC_GAPS[@]} flagged task(s) have no '<CODE>_CHECKER' grant in BANK_ROLE_GRANTS:"
    for t in "${MC_GAPS[@]}"; do log "          ${t} -> nobody holds ${t}_CHECKER"; done
    log "        Those commands will park where only a CHECKER_SUPER_USER can approve them."
  else
    log "  every flagged task has a checker grant"
  fi
else
  log "  MAKER_CHECKER_TASKS not set — no tasks flagged"
fi

# THE INVARIANT. Not configurable, and re-asserted on every run even when this
# cell never enables maker-checker — because the flag can also be set from the
# console, by someone flagging "everything that sounds important".
#
# What happens if one of these IS flagged: Fineract runs the handler, rolls the
# transaction back, parks the command for a human, and answers the middleware a
# success-SHAPED 200 {"commandId":N,"rollbackTransaction":true} with no
# resourceId. An automated rail has no second human, so the customer's deposit
# never completes. (The middleware refuses that response as an UNKNOWN outcome
# rather than mis-reporting success — see FineractClient.failIfParkedByMakerChecker
# — but the rail is still stalled until the flag comes off.)
log "  asserting the middleware's own permission codes are NOT maker-checkerable ..."
PROTECTED_CODES=("${READ_PERMS[@]}" "${WRITE_PERMS[@]}")
FLAGGED_CODES=$(api GET "/v1/permissions?makerCheckerable=true" \
  | jq -r '.[]? | select(.selected == true) | .code // empty')
mapfile -t VIOLATIONS < <(mc_violations "$FLAGGED_CODES" "${PROTECTED_CODES[@]}")
if ((${#VIOLATIONS[@]})); then
  log "  !! ${#VIOLATIONS[@]} middleware code(s) were maker-checker flagged: ${VIOLATIONS[*]}"
  log "  !! An automated rail cannot satisfy dual control — unflagging now."
  api PUT "/v1/permissions" "$(json_flag_map false "${VIOLATIONS[@]}")" >/dev/null
  log "  !! unflagged. If this keeps coming back, someone is flagging them in the console."
else
  log "  clear — all ${#PROTECTED_CODES[@]} middleware codes are unflagged"
fi

if [[ "${ENABLE_MAKER_CHECKER:-0}" == "1" ]]; then
  MC_STATE=$(api GET "/v1/configurations/name/maker-checker" | jq -r '.enabled // false')
  if [[ "$MC_STATE" == "true" ]]; then
    log "  global maker-checker already enabled"
  else
    api PUT "/v1/configurations/name/maker-checker" '{"enabled":true}' >/dev/null
    log "  global maker-checker ENABLED (takes effect immediately — the config cache is evicted)"
  fi
else
  log "  ENABLE_MAKER_CHECKER=0 — global switch left OFF (every per-task flag above is inert until it is on)"
fi

if [[ "${RUN_SMOKE:-0}" == "1" ]]; then
  log "7/7 smoke: driving the adapter's exact call sequence with the mw credentials ..."
  TODAY=$(date -u +%Y-%m-%d) REF="smoke-$(date -u +%s)"
  CLIENT_EXT="smoke-probe-$(date -u +%s)"
  CLIENT_RESP=$(api POST "/v1/clients" "$(jq -n --arg ext "$CLIENT_EXT" --arg d "$TODAY" '{
        officeId:1, firstname:"SMOKE", lastname:"Probe", externalId:$ext,
        legalFormId:1, active:true, activationDate:$d,
        locale:"en", dateFormat:"yyyy-MM-dd"
      }')" innbucks-mw-write "$MW_WRITE_PASSWORD")
  CLIENT_ID=$(jq -r '.clientId // .resourceId // empty' <<<"$CLIENT_RESP")
  if [[ -z "$CLIENT_ID" ]]; then
    # A 2xx with no id means MAKER-CHECKER: the command was parked for a human
    # approver and its transaction rolled back (CommandSourceService.processCommand
    # -> RollbackTransactionNotApprovedException). The caller gets a
    # success-SHAPED response and no row. An automated rail cannot satisfy dual
    # control, so the middleware's own commands must be exempt — leaving every
    # human-facing permission under maker-checker untouched.
    fail "client create returned 2xx with no clientId/resourceId:
         ${CLIENT_RESP}
       That is maker-checker parking the command for approval, not a create.
       Check (note the column is can_maker_checker, and the global key is kebab-case):
         ${BD_SQL} \\
           -c \"SELECT name, enabled FROM c_configuration WHERE name = 'maker-checker';\"
         ${BD_SQL} \\
           -c \"SELECT code, can_maker_checker FROM m_permission WHERE code IN ('${WRITE_PERMS[0]}'$(printf ", '%s'" "${WRITE_PERMS[@]:1}"));\"
       Exempt ONLY the codes this middleware issues, then restart Fineract:
         ${BD_SQL} \\
           -c \"UPDATE m_permission SET can_maker_checker = false WHERE code IN ('${WRITE_PERMS[0]}'$(printf ", '%s'" "${WRITE_PERMS[@]:1}"));\"
         docker compose restart fineract"
  fi
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
    "$(jq -n --arg d "$TODAY" --arg ref "$REF" --argjson pt "$PAYMENT_TYPE_ID" '{
        transactionDate:$d, transactionAmount:1.00, externalId:$ref, paymentTypeId:$pt,
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
  FINERACT_PAYMENT_TYPE_ID=${PAYMENT_TYPE_ID}
  FINERACT_CURRENCY=${CELL_CURRENCY}
  FINERACT_OFFICE_ID=1
  FINERACT_READ_USERNAME=innbucks-mw-read
  FINERACT_WRITE_USERNAME=innbucks-mw-write
  (passwords: the values you provided in MW_READ_PASSWORD / MW_WRITE_PASSWORD)
EOF
