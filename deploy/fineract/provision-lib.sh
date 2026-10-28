#!/usr/bin/env bash
# Pure helpers for provision-cell.sh — no network, no state, no side effects,
# so provision-selftest.sh can cover them without a cell.
#
# Only the parsing and set arithmetic lives here. Anything that talks to
# Fineract stays in provision-cell.sh, where it is obvious that it does.

# split_list SEP STRING — one item per line, whitespace-trimmed, blanks dropped.
# Used for the '|'- and ','-separated policy lists in a cell file, where a
# trailing separator or a space after a comma is an ordinary typo rather than
# something an operator should have to think about.
split_list() {
  local sep="$1" str="${2:-}" item
  [[ -n "$str" ]] || return 0
  while IFS= read -r item; do
    item="${item#"${item%%[![:space:]]*}"}"   # ltrim
    item="${item%"${item##*[![:space:]]}"}"   # rtrim
    [[ -n "$item" ]] && printf '%s\n' "$item"
  done < <(tr "$sep" '\n' <<<"$str")
  return 0
}

# parse_role_grants STRING — "Role A=X,Y;Role B=Z" -> "Role A<TAB>X,Y" per line.
# A malformed entry (no '=') is dropped and reported on stderr rather than
# silently swallowed: a role that never gets its codes authenticates fine and is
# refused everywhere, which is painful to diagnose from the far end.
#
# Role names may contain spaces ("Test Risk Manager"); codes may not, so the
# split is on the FIRST '=' only.
parse_role_grants() {
  local str="${1:-}" entry role codes
  [[ -n "$str" ]] || return 0
  while IFS= read -r entry; do
    [[ -n "$entry" ]] || continue
    if [[ "$entry" != *=* ]]; then
      printf 'malformed role grant (expected Role=CODE,CODE): %s\n' "$entry" >&2
      continue
    fi
    role="${entry%%=*}"; codes="${entry#*=}"
    role="${role#"${role%%[![:space:]]*}"}"; role="${role%"${role##*[![:space:]]}"}"
    [[ -n "$role" && -n "$codes" ]] || {
      printf 'role grant with empty role or codes: %s\n' "$entry" >&2
      continue
    }
    printf '%s\t%s\n' "$role" "$codes"
  done < <(split_list ';' "$str")
  return 0
}

# mc_violations FLAGGED_LIST PROTECTED... — prints each PROTECTED code that
# appears in FLAGGED_LIST (a newline-separated set), one per line.
#
# THE safety-critical function in this kit. It answers "is any permission the
# middleware issues currently maker-checkerable", and a false negative here is
# the difference between a working customer rail and every deposit parking for
# a human approver who does not exist.
#
# grep -x -F, never a substring match: DEPOSIT_SAVINGSACCOUNT must not be
# considered flagged merely because DEPOSIT_SAVINGSACCOUNT_CHECKER is.
mc_violations() {
  local flagged="${1:-}"; shift || true
  local code
  [[ -n "$flagged" ]] || return 0
  for code in "$@"; do
    [[ -n "$code" ]] || continue
    if grep -qxF -- "$code" <<<"$flagged"; then printf '%s\n' "$code"; fi
  done
  return 0
}

# suggest_codes CODE ALL_CODES — up to 5 codes resembling CODE, space-joined,
# for the "did you mean" half of an unknown-permission warning. Empty when
# nothing resembles it.
#
# ALWAYS SUCCEEDS, and that is the entire point. Its caller runs under
# 'set -euo pipefail' while reporting a typo, so a naive
#     near=$(grep -i "$frag" <<<"$all" | head -5 | paste -sd' ' -)
# kills the whole provision run SILENTLY in two ordinary cases: grep matching
# nothing (exit 1), and head closing early so grep dies of SIGPIPE (141) — the
# same trap gen_password documents. Either one exits BEFORE the warning it was
# computing, and takes steps 6c-6e with it, including the maker-checker
# assertion. A missing hint is cosmetic; a skipped assertion is not.
#
# Hence: grep neutralised with '|| true', 'awk NR<=5' instead of head (it drains
# the stream rather than closing it), and an unconditional 'return 0'.
#
# -F, not a regex: a permission-code fragment is literal text.
suggest_codes() {
  local code="${1:-}" all="${2:-}" needle out
  needle="${code#*_}"                      # drop the ACTION_, keep the entity
  [[ -n "$needle" && -n "$all" ]] || return 0
  out=$({ grep -iF -- "$needle" <<<"$all" || true; } | awk 'NR<=5' | paste -sd' ' -) || true
  printf '%s' "$out"
  return 0
}

# suggest_roles NAME ALL_NAMES — up to 5 existing role names resembling NAME,
# '|'-joined (role names contain spaces and '/', so a comma would be ambiguous).
#
# Same always-succeeds contract as suggest_codes, for the same reason — see the
# note there. This one matters more in practice: role names are typed by a human
# into a cell file and are the thing most likely to be wrong, so the hint IS the
# fix. On the ZW cell, 'Test Internal Auditor' should have pointed straight at
# 'TEST Internal Audit'.
#
# It RANKS by how many of the name's words a role contains, and that is the
# whole design. A plain "contains any word" filter is useless on a real cell:
# every role there is prefixed "TEST", so every role matches and the hint lists
# all of them. Scoring puts the intended role FIRST — 'Test Internal Auditor'
# scores 2 against 'TEST Internal Audit' and 1 against everything else.
#
# Words shorter than 4 characters are ignored (they match noise), comparison is
# case-insensitive, and ties break alphabetically so the output is deterministic
# and testable. Capped at 3: this is a nudge toward the right name, not a
# directory listing. awk receives the name as a VARIABLE, so no glob or regex
# metacharacter in it can be interpreted.
suggest_roles() {
  local name="${1:-}" all="${2:-}" out
  [[ -n "$name" && -n "$all" ]] || return 0
  out=$(awk -v name="$name" '
      BEGIN { n = split(tolower(name), w, /[^[:alnum:]]+/) }
      {
        role = tolower($0); score = 0
        for (i = 1; i <= n; i++)
          if (length(w[i]) >= 4 && index(role, w[i]) > 0) score++
        if (score > 0) printf "%d\t%s\n", score, $0
      }' <<<"$all" \
    | sort -t"$(printf '\t')" -k1,1nr -k2,2 \
    | awk 'NR<=3' | cut -f2- | paste -sd'|' -) || true
  printf '%s' "$out"
  return 0
}

# checker_gaps TASKS GRANTS — prints each maker-checker task for which no role
# in GRANTS holds the matching '<TASK>_CHECKER'.
#
# Flagging a task and granting its checker are two halves of ONE decision, and
# splitting them across two settings in the same file makes it easy to do one
# and not the other. The ZW cell shipped exactly that: eight tasks flagged, one
# checker granted. Nothing fails — commands park correctly into an inbox no
# non-superuser can act on, which reads to an operator as "maker-checker is
# broken", the same symptom the UAT already reported for a different reason.
#
# Reported as a gap rather than an error: a bank may deliberately route some
# approvals through a CHECKER_SUPER_USER, and five actions have no seeded
# _CHECKER row at all, so this cannot be a hard refusal.
checker_gaps() {
  local tasks="${1:-}" grants="${2:-}" task granted
  [[ -n "$tasks" ]] || return 0
  granted=$({ parse_role_grants "$grants" 2>/dev/null | cut -f2- | tr ',' '\n' || true; } \
            | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')
  while IFS= read -r task; do
    [[ -n "$task" ]] || continue
    grep -qxF -- "${task}_CHECKER" <<<"$granted" || printf '%s\n' "$task"
  done < <(split_list ',' "$tasks")
  return 0
}

# ─── Customer deposits: the core-side lock (steps 5, 6a and 7) ───────────────
#
# POST /transactions/deposit is an UNFUNDED cash-in: any signed-in customer
# credits their own wallet from nothing. The middleware refuses it unless its
# TRANSACTIONS_DEPOSIT_ENABLED is on, but that flag lives in a gitignored .env,
# where one line carried over from staging would reopen money creation with no
# review and no record. This is the SECOND, independent lock: the middleware's
# write AppUser is not granted the one Fineract permission a deposit needs
# unless the box opts in at provision time, so the core refuses the command
# even when the flag is wrong.
#
# The opt-in is PER BOX (MIDDLEWARE_CUSTOMER_DEPOSITS=1 in that box's gitignored
# deploy/fineract/.env), never in a committed cell file: one cell file serves
# every box of a market (staging and production both source cell.zw.env), so a
# committed switch would be on for both or off for both.
CUSTOMER_DEPOSIT_PERMISSION=DEPOSIT_SAVINGSACCOUNT

# parse_deposit_opt_in VALUE — prints 1 (opted in) or 0 (off).
#
# Unset or empty is 0: production is safe with no action. Only the literal 1
# opts in. Any other spelling (true, yes, on, ' 1') RETURNS 1 and prints
# nothing, and the caller must stop: guessing is wrong in both directions.
# Read as off, a staging box that wrote "true" silently loses deposits; read as
# on, a typo anywhere reopens money creation.
parse_deposit_opt_in() {
  case "${1-}" in
    ''|0) printf '0' ;;
    1)    printf '1' ;;
    *)    return 1 ;;
  esac
}

# write_role_grants OPT_IN CODE... — the codes to GRANT the middleware's write
# role: CODE... in order, minus the customer-deposit permission unless OPT_IN is
# exactly 1 (anything else is treated as off, failing closed).
#
# Step 6e protects exactly these codes (plus the read role's), so the deposit
# permission is shielded from maker-checker only on a box that grants it. Where
# the middleware cannot deposit, the bank is free to dual-control teller
# deposits, and 6e will not unflag them.
write_role_grants() {
  local opt_in="${1:-0}" code; shift || true
  for code in "$@"; do
    [[ -n "$code" ]] || continue
    [[ "$opt_in" != 1 && "$code" == "$CUSTOMER_DEPOSIT_PERMISSION" ]] && continue
    printf '%s\n' "$code"
  done
  return 0
}

# write_role_revokes OPT_IN — the codes to actively REMOVE from the write role:
# the deposit permission unless OPT_IN is exactly 1, else nothing.
#
# Withholding a grant is not enough on its own. A cell provisioned before this
# lock already has the permission on the role, and PUT /roles/{id}/permissions
# MERGES, so only an explicit false takes it away (Role.updatePermission ->
# removePermission). This is the one code the script revokes; every other grant
# stays additive.
write_role_revokes() {
  [[ "${1:-0}" == 1 ]] || printf '%s\n' "$CUSTOMER_DEPOSIT_PERMISSION"
  return 0
}

# deposit_capable_codes HELD — prints each code in HELD (newline-separated; the
# union of every role the write AppUser holds) that lets its holder post a
# savings deposit: the deposit permission itself, or ALL_FUNCTIONS, which
# AppUser.hasPermissionTo honours ahead of every specific code. Empty means the
# core refuses the AppUser's deposits.
#
# Exact match, never substring: DEPOSIT_SAVINGSACCOUNT_CHECKER grants no deposit.
# Case-insensitive, because Fineract compares codes that way
# (Permission.hasCode -> equalsIgnoreCase).
deposit_capable_codes() {
  local held="${1:-}" code
  [[ -n "$held" ]] || return 0
  for code in "$CUSTOMER_DEPOSIT_PERMISSION" ALL_FUNCTIONS; do
    if grep -qixF -- "$code" <<<"$held"; then printf '%s\n' "$code"; fi
  done
  return 0
}

# classify_deposit_probe STATUS BODY — reads the answer to the smoke step's
# zero-amount deposit, sent with the write AppUser's credentials on a box that
# has NOT opted in. Prints one word:
#
#   refused   403 carrying error.msg.not.authorized: Fineract's permission check
#             refused the command (AppUser.validateHasPermissionTo, run in
#             logCommandSource before the command executes). The lock holds.
#   allowed   the permission check PASSED: a 400 (amount 0 fails positiveAmount),
#             a 403 domain veto with another code, or a 2xx. The lock is NOT in
#             place. Nothing moved: an amount of 0 cannot pass validation.
#   unproven  anything that says nothing about the permission (connection
#             failure, 401, 404, 5xx).
classify_deposit_probe() {
  local status="${1:-000}" body="${2:-}"
  case "$status" in
    403) if grep -qF -- 'error.msg.not.authorized' <<<"$body"; then printf 'refused'; else printf 'allowed'; fi ;;
    2??|400) printf 'allowed' ;;
    *) printf 'unproven' ;;
  esac
  return 0
}

# committed_deposit_opt_ins FILE... — prints each FILE that ASSIGNS
# MIDDLEWARE_CUSTOMER_DEPOSITS (a comment mentioning it does not count).
#
# A committed cell file is shared by every box of its market, so an opt-in
# there opts PRODUCTION in on its next provision. provision-cell.sh refuses to
# run while any committed cell file carries one.
committed_deposit_opt_ins() {
  local f
  for f in "$@"; do
    [[ -f "$f" ]] || continue
    if grep -qE '^[[:space:]]*(export[[:space:]]+)?MIDDLEWARE_CUSTOMER_DEPOSITS[[:space:]]*=' "$f"; then
      printf '%s\n' "$f"
    fi
  done
  return 0
}

# json_flag_map BOOL CODE... — {"permissions":{"CODE":BOOL,...}}, the body shape
# PUT /v1/permissions takes for both flagging and unflagging, and the shape
# PUT /v1/roles/{id}/permissions takes for granting (true) and revoking (false).
json_flag_map() {
  local val="$1"; shift
  printf '%s\n' "$@" | jq -R . | jq -s --argjson v "$val" 'map({(.): $v}) | add | {permissions: .}'
}
