#!/usr/bin/env bash
# Regression cover for provision-lib.sh — the parsing and set arithmetic behind
# provision-cell.sh's steps 6b and 6e, and the customer-deposit lock (5, 6b, 6e,
# 6f, 7) — plus a few STRUCTURAL checks on provision-cell.sh itself, where the
# lock depends on which list the script passes where, and in what order.
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

printf 'checker_gaps\n'
# THE gap this exists for: the ZW cell flagged eight tasks and granted ONE
# checker. Nothing errored; seven approval queues simply could never drain.
is "reports a flagged task with no checker grant" \
   "$(checker_gaps 'WRITEOFF_LOAN,APPROVE_LOAN' 'Ops=WRITEOFF_LOAN_CHECKER')" \
   "APPROVE_LOAN"
is "silent when every task is covered" \
   "$(checker_gaps 'WRITEOFF_LOAN,APPROVE_LOAN' 'A=WRITEOFF_LOAN_CHECKER;B=APPROVE_LOAN_CHECKER')" \
   ""
is "the MAKER grant is not mistaken for the checker" \
   "$(checker_gaps 'WRITEOFF_LOAN' 'Ops=WRITEOFF_LOAN')" \
   "WRITEOFF_LOAN"
is "checkers may share one role" \
   "$(checker_gaps 'CREATE_USER,APPROVE_LOAN' 'A=CREATE_USER_CHECKER,APPROVE_LOAN_CHECKER')" \
   ""
is "no grants at all reports every task" \
   "$(checker_gaps 'CREATE_USER,APPROVE_LOAN' '')" \
   "$(printf 'CREATE_USER\nAPPROVE_LOAN')"
is "no tasks is silent" "$(checker_gaps '' 'A=CREATE_USER_CHECKER')" ""
is "never kills a set -e caller" \
   "$( ( set -euo pipefail; . ./provision-lib.sh
         g=$(checker_gaps 'CREATE_USER' ''); printf 'alive:%s' "$g" ) )" \
   "alive:CREATE_USER"

printf 'json_flag_map\n'
if command -v jq >/dev/null; then
  is "flags true"  "$(json_flag_map true  A B | jq -c .)" '{"permissions":{"A":true,"B":true}}'
  is "flags false" "$(json_flag_map false A   | jq -c .)" '{"permissions":{"A":false}}'
  # The deposit lock's revoke rides the same body: false REMOVES a role's code.
  is "revoke body for the write role" \
     "$(json_flag_map false $(write_role_revokes 0) | jq -c .)" \
     '{"permissions":{"DEPOSIT_SAVINGSACCOUNT":false}}'
else
  printf '  skip json_flag_map (jq not installed)\n'
fi

# ─── The customer-deposit lock ───────────────────────────────────────────────
# POST /transactions/deposit credits a wallet from nothing. These cases pin the
# core-side lock: production (no opt-in) neither grants nor keeps
# DEPOSIT_SAVINGSACCOUNT, and only a box that says exactly 1 gets it.
printf 'parse_deposit_opt_in\n'
is "unset is OFF"   "$(parse_deposit_opt_in)"    "0"
is "empty is OFF"   "$(parse_deposit_opt_in '')" "0"
is "0 is OFF"       "$(parse_deposit_opt_in 0)"  "0"
is "1 is ON"        "$(parse_deposit_opt_in 1)"  "1"
# Anything else is REFUSED, never guessed: read as off, a staging box that wrote
# "true" silently loses deposits; read as on, a typo reopens money creation.
for v in true yes on TRUE ' 1' '1 ' 2 -1 'MIDDLEWARE_CUSTOMER_DEPOSITS=1'; do
  is "refuses $(printf '%q' "$v")" \
     "$(parse_deposit_opt_in "$v" >/dev/null && echo accepted || echo refused)" "refused"
done
is "a refusal prints no value a careless caller could use" \
   "$(parse_deposit_opt_in true || true)" ""
# Fail closed by default: a caller that forgets the '|| fail' still stops. The
# subshell must NOT sit on the left of '||' here, because bash suspends set -e
# for anything whose status a '||' tests, and the case would pass vacuously.
STOP_OUT=$( ( set -euo pipefail; . ./provision-lib.sh
              v=$(parse_deposit_opt_in yes); printf 'continued:%s' "$v" ) 2>/dev/null )
is "a refusal stops a set -e caller that does not handle it" "${STOP_OUT:-stopped}" "stopped"

printf 'write_role_grants / write_role_revokes\n'
MW_WRITE=(CREATE_CLIENT ACTIVATE_CLIENT CREATE_SAVINGSACCOUNT APPROVE_SAVINGSACCOUNT
          ACTIVATE_SAVINGSACCOUNT DEPOSIT_SAVINGSACCOUNT WITHDRAWAL_SAVINGSACCOUNT
          CREATE_ACCOUNTTRANSFER)
is "OFF excludes the deposit permission and keeps the rest, in order" \
   "$(write_role_grants 0 "${MW_WRITE[@]}")" \
   "$(printf 'CREATE_CLIENT\nACTIVATE_CLIENT\nCREATE_SAVINGSACCOUNT\nAPPROVE_SAVINGSACCOUNT\nACTIVATE_SAVINGSACCOUNT\nWITHDRAWAL_SAVINGSACCOUNT\nCREATE_ACCOUNTTRANSFER')"
is "ON grants every write code, the deposit permission included" \
   "$(write_role_grants 1 "${MW_WRITE[@]}")" \
   "$(printf '%s\n' "${MW_WRITE[@]}")"
# Fail closed: only the literal 1 grants. parse_deposit_opt_in already refuses
# other spellings; this is the second line if a caller skips it.
is "any non-1 opt-in is treated as OFF" \
   "$(write_role_grants true "${MW_WRITE[@]}" | grep -c DEPOSIT_SAVINGSACCOUNT)" "0"
is "the CHECKER sibling is not the deposit permission" \
   "$(write_role_grants 0 DEPOSIT_SAVINGSACCOUNT_CHECKER CREATE_CLIENT)" \
   "$(printf 'DEPOSIT_SAVINGSACCOUNT_CHECKER\nCREATE_CLIENT')"
is "OFF revokes the deposit permission (an older cell's role still holds it)" \
   "$(write_role_revokes 0)" "DEPOSIT_SAVINGSACCOUNT"
is "unset opt-in revokes too" "$(write_role_revokes)" "DEPOSIT_SAVINGSACCOUNT"
is "ON revokes nothing"       "$(write_role_revokes 1)" ""

printf 'mw_write_grants / mw_protected_codes (what provision-cell.sh actually uses)\n'
# The script takes its grant list and 6e's protected set from these two and
# nothing else (pinned under "provision-cell.sh wiring" below), so these cases
# cover what a cell is really given, not a copy of it.
is "this file's copy of the write codes is the lib's" \
   "$(printf '%s\n' "${WRITE_PERMS[@]}")" "$(printf '%s\n' "${MW_WRITE[@]}")"
is "OFF grants every write code but the deposit permission" \
   "$(mw_write_grants 0)" "$(write_role_grants 0 "${MW_WRITE[@]}")"
is "ON grants every write code" \
   "$(mw_write_grants 1)" "$(printf '%s\n' "${WRITE_PERMS[@]}")"
is "unset opt-in grants as OFF" "$(mw_write_grants)" "$(mw_write_grants 0)"
is "OFF protects the read codes, then the grants" \
   "$(mw_protected_codes 0)" "$(printf '%s\n' "${READ_PERMS[@]}"; mw_write_grants 0)"
is "OFF does not protect the deposit permission" \
   "$(mw_protected_codes 0 | grep -cxF DEPOSIT_SAVINGSACCOUNT)" "0"
is "ON protects the deposit permission" \
   "$(mw_protected_codes 1 | grep -cxF DEPOSIT_SAVINGSACCOUNT)" "1"
is "every other adapter code is protected either way" \
   "$(comm -3 <(mw_protected_codes 0 | sort) <(mw_protected_codes 1 | sort) | tr -d '[:space:]')" \
   "DEPOSIT_SAVINGSACCOUNT"

printf 'step 6e protects what step 5 grants\n'
# 6e's protected set is mw_protected_codes (READ_PERMS + the write role's
# grants), so a flagged DEPOSIT_SAVINGSACCOUNT is the middleware's problem only
# where the middleware can deposit. Elsewhere it is the bank's teller-deposit
# code, and 6e must not unflag the bank's dual control on it.
FLAGGED=$(printf 'DEPOSIT_SAVINGSACCOUNT\nCREATE_JOURNALENTRY')
is "ON: a flagged deposit permission is a violation" \
   "$(mc_violations "$FLAGGED" $(mw_protected_codes 1))" \
   "DEPOSIT_SAVINGSACCOUNT"
is "OFF: a flagged deposit permission is the bank's, not a violation" \
   "$(mc_violations "$FLAGGED" $(mw_protected_codes 0))" \
   ""
is "OFF: every other middleware code is still protected" \
   "$(mc_violations "$(printf 'CREATE_ACCOUNTTRANSFER\nDEPOSIT_SAVINGSACCOUNT')" $(mw_protected_codes 0))" \
   "CREATE_ACCOUNTTRANSFER"

printf 'deposit_capable_codes\n'
is "the deposit permission is capable" \
   "$(deposit_capable_codes "$(printf 'CREATE_CLIENT\nDEPOSIT_SAVINGSACCOUNT')")" "DEPOSIT_SAVINGSACCOUNT"
# AppUser.hasPermissionTo lets ALL_FUNCTIONS stand in for every code, so a role
# carrying it opens the lock as surely as the code itself.
is "ALL_FUNCTIONS is capable" \
   "$(deposit_capable_codes "$(printf 'READ_CLIENT\nALL_FUNCTIONS')")" "ALL_FUNCTIONS"
is "both are reported" \
   "$(deposit_capable_codes "$(printf 'ALL_FUNCTIONS\nDEPOSIT_SAVINGSACCOUNT')")" \
   "$(printf 'DEPOSIT_SAVINGSACCOUNT\nALL_FUNCTIONS')"
is "the CHECKER sibling grants no deposit" \
   "$(deposit_capable_codes 'DEPOSIT_SAVINGSACCOUNT_CHECKER')" ""
is "ALL_FUNCTIONS_READ grants no deposit" \
   "$(deposit_capable_codes 'ALL_FUNCTIONS_READ')" ""
# Fineract matches codes case-insensitively (Permission.hasCode).
is "case-insensitive, like Fineract" \
   "$(deposit_capable_codes 'deposit_savingsaccount')" "DEPOSIT_SAVINGSACCOUNT"
is "a locked write role is empty" \
   "$(deposit_capable_codes "$(write_role_grants 0 "${MW_WRITE[@]}")")" ""
is "nothing held is empty" "$(deposit_capable_codes '')" ""
is "never kills a set -e caller" \
   "$( ( set -euo pipefail; . ./provision-lib.sh
         c=$(deposit_capable_codes 'READ_CLIENT'); printf 'alive:%s' "$c" ) )" \
   "alive:"

printf 'classify_deposit_probe\n'
# The 403 body is Fineract's NoAuthorizationExceptionMapper shape (see
# FineractClientContractTest.PERMISSION_DENIED_BODY).
NOT_AUTH='{"userMessageGlobalisationCode":"error.msg.not.authorized","errors":[{"userMessageGlobalisationCode":"error.msg.not.authorized","defaultUserMessage":"User has no authority to: DEPOSIT_SAVINGSACCOUNT"}]}'
VETO='{"userMessageGlobalisationCode":"validation.msg.domain.rule.violation","errors":[{"userMessageGlobalisationCode":"error.msg.savingsaccount.transaction.account.is.not.active"}]}'
is "permission refusal: the lock holds"       "$(classify_deposit_probe 403 "$NOT_AUTH")" "refused"
# Amount 0 fails positiveAmount: a 400 means the permission check PASSED.
is "a validation 400 means the lock is open"  "$(classify_deposit_probe 400 '{"errors":[]}')" "allowed"
is "a domain veto 403 means the lock is open" "$(classify_deposit_probe 403 "$VETO")" "allowed"
is "a 2xx means the lock is open"             "$(classify_deposit_probe 200 '{"resourceId":9}')" "allowed"
is "no answer proves nothing"                 "$(classify_deposit_probe 000 '')" "unproven"
is "bad credentials prove nothing"            "$(classify_deposit_probe 401 '{}')" "unproven"
is "a missing account proves nothing"         "$(classify_deposit_probe 404 '{}')" "unproven"
is "a server error proves nothing"            "$(classify_deposit_probe 500 '{}')" "unproven"

printf 'committed_deposit_opt_ins\n'
TMP=$(mktemp -d)
printf 'CELL_CURRENCY=USD\n# MIDDLEWARE_CUSTOMER_DEPOSITS=1 never goes here\n' >"$TMP/clean.env"
printf 'CELL_CURRENCY=USD\nMIDDLEWARE_CUSTOMER_DEPOSITS=1\n'                   >"$TMP/plain.env"
printf '  export MIDDLEWARE_CUSTOMER_DEPOSITS=0\n'                            >"$TMP/export.env"
printf 'MIDDLEWARE_CUSTOMER_DEPOSITS_NOTE=x\n'                               >"$TMP/other.env"
is "a comment is not an assignment" "$(committed_deposit_opt_ins "$TMP/clean.env")" ""
is "an assignment is found"         "$(committed_deposit_opt_ins "$TMP/plain.env")" "$TMP/plain.env"
# Even =0 is refused: the key does not belong in a shared file at all, and a
# later edit of the value is a one-character diff.
is "export and any value count"     "$(committed_deposit_opt_ins "$TMP/export.env")" "$TMP/export.env"
is "a longer key is not the key"    "$(committed_deposit_opt_ins "$TMP/other.env")" ""
is "a missing file is skipped"      "$(committed_deposit_opt_ins "$TMP/absent.env")" ""
rm -rf "$TMP"
# THE guard that matters: the real committed cell files. One of them is
# sourced by production.
is "no committed cell file opts in" "$(committed_deposit_opt_ins ../cells/*.env)" ""

printf 'is_middleware_role (step 6b refuses these)\n'
# A cell file is shared by every box of its market. Granting the middleware's
# write role from one is how a committed line re-opened production's deposit
# lock past the per-box opt-in (step 5 revoked, 6b granted it back), so 6b
# refuses any entry naming either middleware role.
role_verdict() { is_middleware_role "$1" && printf 'refused' || printf 'granted'; }
is "the write role"                 "$(role_verdict innbucks-mw-write)" "refused"
is "the read role"                  "$(role_verdict innbucks-mw-read)"  "refused"
is "case does not get one through"  "$(role_verdict INNBUCKS-MW-WRITE)" "refused"
is "nor surrounding whitespace"     "$(role_verdict '  innbucks-mw-write ')" "refused"
is "a bank role is granted"         "$(role_verdict 'TEST Risk Manager')" "granted"
is "a longer name is another role"  "$(role_verdict innbucks-mw-writer)" "granted"
is "a prefix is another role"       "$(role_verdict innbucks-mw)" "granted"
is "empty is not a middleware role" "$(role_verdict '')" "granted"
is "the names are the script's users" "${MW_READ_ROLE} ${MW_WRITE_ROLE}" "innbucks-mw-read innbucks-mw-write"
# THE committed-file half: no committed cell file may name one. The script
# would refuse it and fail; this fails the change that adds it.
MW_GRANTS_IN_CELL_FILES=""
for f in ../cells/*.env; do
  # Sourced WITHOUT -u (the operator's shell has none), and a file that cannot
  # be read fails the check: under this script's -u a ${HOST_VALUE} anywhere
  # after the grants line killed the subshell silently, so the grants came
  # back empty and a middleware-role grant passed.
  if ! grants=$( set +u; set -a; . "$f" >/dev/null 2>&1 || exit 97; printf '%s' "${BANK_ROLE_GRANTS:-}" ); then
    MW_GRANTS_IN_CELL_FILES+="${f##*/}:unreadable "; continue
  fi
  while IFS=$'\t' read -r role _; do
    if is_middleware_role "$role"; then MW_GRANTS_IN_CELL_FILES+="${f##*/}:${role} "; fi
  done < <(parse_role_grants "$grants" 2>/dev/null)
done
is "no committed cell file grants the middleware's roles" "$MW_GRANTS_IN_CELL_FILES" ""

# ─── provision-cell.sh wiring ────────────────────────────────────────────────
# The lib cases above prove the sets are right. These prove the script USES
# them, where a refactor that passed WRITE_PERMS to the write role, or rebuilt
# 6e's set by hand, or moved 6f back above 6b, would keep every case above
# green while opening the lock on production. Each check requires a POSITIVE
# match, so a reworded line fails here rather than passing vacuously: update
# the check with the line, deliberately.
printf 'provision-cell.sh wiring\n'
CELL=provision-cell.sh
count_lines() { grep -cF -- "$1" "$CELL"; }
is "the write role is ensured with the opt-in-filtered grants" \
   "$(grep -F 'ensure_role "$MW_WRITE_ROLE"' "$CELL" | grep -cF '"${WRITE_GRANTS[@]}"')" "1"
is "no role is ever ensured with the unfiltered WRITE_PERMS" \
   "$(grep -F 'ensure_role ' "$CELL" | grep -cF 'WRITE_PERMS')" "0"
is "the grants come from mw_write_grants" \
   "$(count_lines 'mapfile -t WRITE_GRANTS  < <(mw_write_grants "$MW_DEPOSITS")')" "1"
is "step 5 revokes from the write role" \
   "$(count_lines 'api PUT "/v1/roles/${WRITE_ROLE_ID}/permissions" "$(json_flag_map false "${WRITE_REVOKES[@]}")"')" "1"
is "6e's protected set comes from mw_protected_codes" \
   "$(count_lines 'mapfile -t PROTECTED_CODES < <(mw_protected_codes "$MW_DEPOSITS")')" "1"
is "6e checks the flagged codes against that set" \
   "$(count_lines 'mc_violations "$FLAGGED_CODES" "${PROTECTED_CODES[@]}"')" "1"
is "6b refuses the middleware's roles" \
   "$(count_lines 'if is_middleware_role "$role_name"; then')" "1"
# THE ordering. 6f must read the state the run leaves behind, so no role or
# user write may come after it (it once ran before 6b, and 6b granted the
# deposit permission back after 6f had printed LOCKED). The smoke comes after
# it and writes clients and wallets, never roles or users.
VERIFY_AT=$(grep -nF 'log "6f/7 verifying the customer-deposit lock' "$CELL" | cut -d: -f1)
LAST_ROLE_WRITE_AT=$(grep -nE 'api (PUT|POST|DELETE) "/v1/(roles|users)' "$CELL" | tail -1 | cut -d: -f1)
SMOKE_AT=$(grep -nF 'log "7/7 smoke' "$CELL" | cut -d: -f1)
is "6f exists, once" "$(count_lines 'log "6f/7 verifying the customer-deposit lock')" "1"
is "6f comes after every role and user write" \
   "$( (( ${VERIFY_AT:-0} > ${LAST_ROLE_WRITE_AT:-999999} )) && echo after || echo before)" "after"
is "6f comes before the smoke" \
   "$( (( ${VERIFY_AT:-999999} < ${SMOKE_AT:-0} )) && echo before || echo after)" "before"

printf '\n%d passed, %d failed\n' "$PASS" "$FAIL"
[[ "$FAIL" -eq 0 ]]
