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

# json_flag_map BOOL CODE... — {"permissions":{"CODE":BOOL,...}}, the body shape
# PUT /v1/permissions takes for both flagging and unflagging.
json_flag_map() {
  local val="$1"; shift
  printf '%s\n' "$@" | jq -R . | jq -s --argjson v "$val" 'map({(.): $v}) | add | {permissions: .}'
}
