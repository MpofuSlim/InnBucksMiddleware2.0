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

printf 'suggest_codes\n'
CODES=$(printf 'READ_CLIENT\nCREATE_CLIENT\nDEPOSIT_SAVINGSACCOUNT\nWITHDRAWAL_SAVINGSACCOUNT\nDEPOSIT_SAVINGSACCOUNT_CHECKER\nBLOCKDEBIT_SAVINGSACCOUNT\nUNBLOCKDEBIT_SAVINGSACCOUNT\nAPPROVE_SAVINGSACCOUNT')
is "suggests on the entity half" \
   "$(suggest_codes 'FROBNICATE_CLIENT' "$CODES")" \
   "READ_CLIENT CREATE_CLIENT"
is "no resemblance is empty, not an error" \
   "$(suggest_codes 'FROBNICATE_WIDGET' "$CODES")" \
   ""
is "empty inputs" "$(suggest_codes '' "$CODES")$(suggest_codes 'X_Y' '')" ""
# 6 codes contain SAVINGSACCOUNT; the hint is capped at 5.
is "caps the hint at 5" \
   "$(suggest_codes 'BOGUS_SAVINGSACCOUNT' "$CODES" | wc -w | tr -d ' ')" \
   "5"

# THE regression. Both of these shapes previously killed provision-cell.sh
# outright — under 'set -euo pipefail' a no-match grep exits 1 and an early-
# closing head SIGPIPEs grep into 141, and the script died mid-step 6b with no
# message, silently skipping 6c-6e and the maker-checker assertion with it.
# Run in a subshell with -e ON, so a regression fails here instead of on a cell.
survives() { ( set -euo pipefail; . ./provision-lib.sh
               near=$(suggest_codes "$1" "$2"); printf 'alive:%s' "$near" ); }
is "no-match does not kill a set -e caller" \
   "$(survives 'FROBNICATE_WIDGET' "$CODES")" "alive:"
is "overflowing match does not kill a set -e caller" \
   "$(survives 'BOGUS_SAVINGSACCOUNT' "$CODES" | cut -d' ' -f1)" "alive:DEPOSIT_SAVINGSACCOUNT"

printf 'suggest_roles\n'
ROLES=$(printf 'SUPER USER\nTEST Teller\nTEST Internal Audit\nTEST Risk Manager\nTEST Operations Manager\nTEST Operations Manager Checker\nTEST Branch Operations Supervisor / Branch Manager\ninnbucks-mw-read')
# THE case this exists for. The ZW cell file said "Test Internal Auditor"; the
# cell has "TEST Internal Audit". Every role on that cell begins "TEST", so a
# match-any-word filter would list all of them and help nobody — the intended
# role must come FIRST, which is what the scoring buys.
is "puts the intended role first despite a shared TEST prefix" \
   "$(suggest_roles 'Test Internal Auditor' "$ROLES" | cut -d'|' -f1)" \
   "TEST Internal Audit"
is "case-insensitive, and an exact name still ranks first" \
   "$(suggest_roles 'test risk manager' "$ROLES" | cut -d'|' -f1)" \
   "TEST Risk Manager"
is "capped at three suggestions" \
   "$(suggest_roles 'TEST Manager' "$ROLES" | tr '|' '\n' | grep -c .)" \
   "3"
is "nothing alike is empty" "$(suggest_roles 'Zzzz Qqqq' "$ROLES")" ""
is "short words are ignored"  "$(suggest_roles 'a of the' "$ROLES")" ""
is "empty inputs" "$(suggest_roles '' "$ROLES")$(suggest_roles 'X' '')" ""
is "a glob in the name does not expand" "$(suggest_roles '*' "$ROLES")" ""
is "never kills a set -e caller" \
   "$( ( set -euo pipefail; . ./provision-lib.sh
         n=$(suggest_roles 'Zzzz Qqqq' "$ROLES"); printf 'alive:%s' "$n" ) )" \
   "alive:"

printf 'json_flag_map\n'
if command -v jq >/dev/null; then
  is "flags true"  "$(json_flag_map true  A B | jq -c .)" '{"permissions":{"A":true,"B":true}}'
  is "flags false" "$(json_flag_map false A   | jq -c .)" '{"permissions":{"A":false}}'
else
  printf '  skip json_flag_map (jq not installed)\n'
fi

printf '\n%d passed, %d failed\n' "$PASS" "$FAIL"
[[ "$FAIL" -eq 0 ]]
