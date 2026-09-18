#!/usr/bin/env bash
# Regression cover for provision-lib.sh — the parsing and set arithmetic behind
# provision-cell.sh's steps 6b and 6e.
#
# Needs no cell, no Docker and no network. Run it after touching provision-lib.sh
# or the policy format in deploy/cells/cell.*.env:
#
#     ./provision-selftest.sh
#
# WHY THIS EXISTS. mc_violations answers "is any permission the middleware issues
# currently maker-checkerable". A FALSE NEGATIVE there is not a cosmetic bug: the
# assertion reports "clear", the operator enables maker-checker, and every
# customer deposit parks for a human approver who does not exist. The substring
# case below (DEPOSIT_SAVINGSACCOUNT vs ..._CHECKER) is exactly how a plain grep
# would produce the opposite error, and the ordering case is how a sloppy split
# would produce the first one.
set -uo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"
# shellcheck source=provision-lib.sh
. ./provision-lib.sh

PASS=0 FAIL=0
ok()   { PASS=$((PASS+1)); printf '  ok   %s\n' "$1"; }
bad()  { FAIL=$((FAIL+1)); printf '  FAIL %s\n     expected: %q\n     actual:   %q\n' "$1" "$2" "$3"; }
is()   { [[ "$2" == "$3" ]] && ok "$1" || bad "$1" "$3" "$2"; }   # is NAME ACTUAL EXPECTED

printf 'split_list\n'
is "comma list"            "$(split_list ',' 'A,B,C')"            "$(printf 'A\nB\nC')"
is "pipe list"             "$(split_list '|' 'A|B')"              "$(printf 'A\nB')"
is "trims whitespace"      "$(split_list ',' ' A , B ')"          "$(printf 'A\nB')"
is "drops empties"         "$(split_list ',' 'A,,B,')"            "$(printf 'A\nB')"
is "empty input"           "$(split_list ',' '')"                 ""
is "unset-safe"            "$(split_list ',')"                    ""
# A role name legitimately contains spaces; only the OUTER separator splits.
is "keeps inner spaces"    "$(split_list ';' 'Test Risk Manager;Ops')" "$(printf 'Test Risk Manager\nOps')"

printf 'parse_role_grants\n'
is "single role" \
   "$(parse_role_grants 'Ops=A,B' 2>/dev/null)" \
   "$(printf 'Ops\tA,B')"
is "two roles" \
   "$(parse_role_grants 'Ops=A;Risk=B,C' 2>/dev/null)" \
   "$(printf 'Ops\tA\nRisk\tB,C')"
is "role name with spaces" \
   "$(parse_role_grants 'Test Risk Manager=BLOCKDEBIT_SAVINGSACCOUNT' 2>/dev/null)" \
   "$(printf 'Test Risk Manager\tBLOCKDEBIT_SAVINGSACCOUNT')"
# Splitting on the FIRST '=' only — a code will never contain '=', but a
# defensive split keeps a stray one from silently truncating the code list.
is "splits on first = only" \
   "$(parse_role_grants 'Ops=A=B' 2>/dev/null)" \
   "$(printf 'Ops\tA=B')"
is "drops entry with no =" \
   "$(parse_role_grants 'Ops=A;GARBAGE;Risk=B' 2>/dev/null)" \
   "$(printf 'Ops\tA\nRisk\tB')"
is "warns on malformed entry" \
   "$(parse_role_grants 'GARBAGE' 2>&1 >/dev/null | head -1)" \
   "malformed role grant (expected Role=CODE,CODE): GARBAGE"
is "drops empty codes" \
   "$(parse_role_grants 'Ops=' 2>/dev/null)" \
   ""
is "empty input" "$(parse_role_grants '' 2>/dev/null)" ""

printf 'mc_violations\n'
MW=(READ_CLIENT READ_SAVINGSACCOUNT READ_ACCOUNTTRANSFER
    CREATE_CLIENT ACTIVATE_CLIENT CREATE_SAVINGSACCOUNT APPROVE_SAVINGSACCOUNT
    ACTIVATE_SAVINGSACCOUNT DEPOSIT_SAVINGSACCOUNT WITHDRAWAL_SAVINGSACCOUNT
    CREATE_ACCOUNTTRANSFER)

is "nothing flagged" \
   "$(mc_violations "$(printf 'CREATE_JOURNALENTRY\nWRITEOFF_LOAN')" "${MW[@]}")" \
   ""
is "one middleware code flagged" \
   "$(mc_violations "$(printf 'CREATE_JOURNALENTRY\nDEPOSIT_SAVINGSACCOUNT')" "${MW[@]}")" \
   "DEPOSIT_SAVINGSACCOUNT"
is "several flagged, in PROTECTED order" \
   "$(mc_violations "$(printf 'WITHDRAWAL_SAVINGSACCOUNT\nCREATE_CLIENT')" "${MW[@]}")" \
   "$(printf 'CREATE_CLIENT\nWITHDRAWAL_SAVINGSACCOUNT')"
is "empty flagged set" "$(mc_violations '' "${MW[@]}")" ""
is "no protected codes"  "$(mc_violations "$(printf 'CREATE_CLIENT')")" ""

# THE case that makes this file worth having. Every middleware code has a
# '<CODE>_CHECKER' sibling, and flagging the CHECKER permission is normal and
# harmless. A substring match would report the rail as broken every time the
# bank set up a checker — and, worse, the reverse mistake (matching
# DEPOSIT_SAVINGSACCOUNT against a flagged list containing only the checker)
# would train an operator to ignore the warning.
is "CHECKER sibling is NOT a violation" \
   "$(mc_violations "$(printf 'DEPOSIT_SAVINGSACCOUNT_CHECKER\nCREATE_CLIENT_CHECKER')" "${MW[@]}")" \
   ""
is "real code flagged alongside its CHECKER still caught" \
   "$(mc_violations "$(printf 'DEPOSIT_SAVINGSACCOUNT_CHECKER\nDEPOSIT_SAVINGSACCOUNT')" "${MW[@]}")" \
   "DEPOSIT_SAVINGSACCOUNT"
# A regex-special character in the haystack must not be interpreted (-F).
is "literal match, not regex" \
   "$(mc_violations "$(printf 'CREATE_CLIENT.*')" "${MW[@]}")" \
   ""

printf 'json_flag_map\n'
if command -v jq >/dev/null; then
  is "flags true"  "$(json_flag_map true  A B | jq -c .)" '{"permissions":{"A":true,"B":true}}'
  is "flags false" "$(json_flag_map false A   | jq -c .)" '{"permissions":{"A":false}}'
else
  printf '  skip json_flag_map (jq not installed)\n'
fi

printf '\n%d passed, %d failed\n' "$PASS" "$FAIL"
[[ "$FAIL" -eq 0 ]]
