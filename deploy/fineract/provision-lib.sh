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

# json_flag_map BOOL CODE... — {"permissions":{"CODE":BOOL,...}}, the body shape
# PUT /v1/permissions takes for both flagging and unflagging.
json_flag_map() {
  local val="$1"; shift
  printf '%s\n' "$@" | jq -R . | jq -s --argjson v "$val" 'map({(.): $v}) | add | {permissions: .}'
}
