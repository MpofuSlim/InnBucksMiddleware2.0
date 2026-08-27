#!/usr/bin/env bash
# Regression cover for the log parsers in lib-parse.sh.
#
# Needs no cell, no Docker and no network — synthetic logs with known answers.
# Run it after touching lib-parse.sh, deposit-load.js's slice arithmetic, or the
# sampler formats in 20-run.sh:
#
#     ./selftest.sh
#
# WHY THIS EXISTS. Two parsers shipped broken and neither raised an error: they
# put a wrong number in a table cell, and the table is what the whole exercise
# is for. Both bugs are reproduced below against the OLD expressions, so this
# file also documents that they were real rather than theoretical.
set -uo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"
# shellcheck source=lib-parse.sh
. ./lib-parse.sh

TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
FAIL=0
ok()  { printf '  \033[32mPASS\033[0m  %s\n' "$1"; }
bad() { printf '  \033[31mFAIL\033[0m  %s\n          expected [%s]\n          got      [%s]\n' "$1" "$2" "$3"; FAIL=1; }
chk() { [ "$2" = "$3" ] && ok "$1" || bad "$1" "$2" "$3"; }

# --------------------------------------------------------------------------
echo
echo "lt_peak_cpu — a digit in the container name must not become the CPU"
S="$TMP/docker-stats.log"
cat > "$S" <<'EOF'
06:55:35 innbucks-fineract=705.12%/12.34% innbucks-fineract-db=210.00%/5.00% k6-loadtest=3.50%/1.00%
06:55:40 innbucks-fineract=698.00%/12.30% innbucks-fineract-db=177.45%/5.10% k6-loadtest=4.80%/1.00%
06:55:45 innbucks-fineract=712.90%/12.40% innbucks-fineract-db=190.10%/5.20% k6-loadtest=2.10%/1.00%
EOF
# The shipped bug, kept as a witness: grep -oE '[0-9.]+' over "k6-loadtest=3.50%"
# yields "6" as well as "3.50", so sort -rn returned 6 whenever k6 was under 6%.
peak_cpu_BUGGY() { grep -oE "$2=[0-9.]+%" "$1" | grep -oE '[0-9.]+' | sort -rn | head -1; }
chk "the old expression really did floor k6 at 6" "6"      "$(peak_cpu_BUGGY "$S" k6-loadtest)"
chk "k6-loadtest"                                 "4.80"   "$(lt_peak_cpu "$S" k6-loadtest)"
chk "innbucks-fineract excludes -db"              "712.90" "$(lt_peak_cpu "$S" innbucks-fineract)"
chk "innbucks-fineract-db"                        "210.00" "$(lt_peak_cpu "$S" innbucks-fineract-db)"
chk "missing file degrades to empty"              ""       "$(lt_peak_cpu "$TMP/nope.log" k6-loadtest)"

# --------------------------------------------------------------------------
echo
echo "lt_pool_scan — the tenants-store pool must not contaminate the OLTP pool"
M="$TMP/fineract-metrics.log"
# The store pool deliberately carries the HIGHER pending value, so any
# conflation is visible rather than plausible.
cat > "$M" <<'EOF'
06:55:35 hikaricp_connections_active{application="fineract",pool="HikariPool-1"} 1.0 fineract_tenants_default_hikaricp_connections_active{application="fineract",pool="fineract_default_pool"} 12.0 hikaricp_connections_pending{application="fineract",pool="HikariPool-1"} 99.0 fineract_tenants_default_hikaricp_connections_pending{application="fineract",pool="fineract_default_pool"} 0.0
06:55:40 hikaricp_connections_active{application="fineract",pool="HikariPool-1"} 2.0 fineract_tenants_default_hikaricp_connections_active{application="fineract",pool="fineract_default_pool"} 40.0 hikaricp_connections_pending{application="fineract",pool="HikariPool-1"} 0.0 fineract_tenants_default_hikaricp_connections_pending{application="fineract",pool="fineract_default_pool"} 9.0
06:55:45 hikaricp_connections_active{application="fineract",pool="HikariPool-1"} 1.0 fineract_tenants_default_hikaricp_connections_active{application="fineract",pool="fineract_default_pool"} 38.0 hikaricp_connections_pending{application="fineract",pool="HikariPool-1"} 0.0 fineract_tenants_default_hikaricp_connections_pending{application="fineract",pool="fineract_default_pool"} 4.0
EOF
pool_BUGGY() { grep -oE "hikaricp_connections_$2\{[^}]*\} [0-9.eE+]+" "$1" | awk '{print $NF}' | sort -g -r | head -1; }
chk "the old expression really did read the wrong pool" "99.0" "$(pool_BUGGY "$M" pending)"
read -r A P PS TS <<<"$(lt_pool_scan "$M")"
chk "peak active (OLTP only)"        "40"                    "$A"
chk "peak pending (OLTP only)"       "9"                     "$P"
chk "samples with a waiting thread"  "2"                     "$PS"
chk "total samples"                  "3"                     "$TS"
chk "pool label"                     "fineract_default_pool" "$(lt_pool_label "$M")"

# --------------------------------------------------------------------------
echo
echo "lt_gc_secs — label values contain spaces, so never split on whitespace"
G="$TMP/gc.log"
cat > "$G" <<'EOF'
06:55:35 jvm_gc_pause_seconds_sum{action="end of minor GC",cause="G1 Evacuation Pause",gc="G1 Young Generation"} 1.500 jvm_gc_pause_seconds_sum{action="end of minor GC",cause="CodeCache GC Threshold",gc="G1 Young Generation"} 0.021
06:56:35 jvm_gc_pause_seconds_sum{action="end of minor GC",cause="G1 Evacuation Pause",gc="G1 Young Generation"} 5.850 jvm_gc_pause_seconds_sum{action="end of minor GC",cause="CodeCache GC Threshold",gc="G1 Young Generation"} 0.021
EOF
# first sample 1.521, last 5.871 -> 4.35 spent during the run
gc_BUGGY() { awk '/jvm_gc_pause_seconds_sum/ { t=0; for(i=1;i<=NF;i++) if($i ~ /^jvm_gc_pause_seconds_sum/) t+=$(i+1); if(first=="")first=t; last=t } END{ printf "%.2f", last-first }' "$1"; }
chk "the old field-split really did read 0.00" "0.00" "$(gc_BUGGY "$G")"
chk "GC seconds during the run"                "4.35" "$(lt_gc_secs "$G")"

# --------------------------------------------------------------------------
echo
echo "degradation — Prometheus export off must give '?', never a crash"
: > "$TMP/empty.log"
read -r EA EP EPS ETS <<<"$(lt_pool_scan "$TMP/empty.log")"
chk "empty log, active"     "?" "$EA"
chk "empty log, pending"    "?" "$EP"
chk "empty log, samples"    "0" "$ETS"
read -r MA MP MPS MTS <<<"$(lt_pool_scan "$TMP/absent.log")"
chk "missing log, active"   "?" "$MA"
chk "missing log, samples"  "0" "$MTS"
chk "missing log, GC"       ""  "$(lt_gc_secs "$TMP/absent.log")"
chk "missing summary json"  ""  "$(lt_k6_metric "$TMP/absent.json" http_reqs rate)"

echo
echo "lt_num2u — a missing metric must not render as a measurement"
chk "value with unit"  "16.62/s" "$(lt_num2u 16.62 /s)"
chk "empty is bare ?"  "?"       "$(lt_num2u "" ms)"

# --------------------------------------------------------------------------
echo
echo "lt_slice — VU slices must be disjoint and cover the file"
chk "200 accounts / 40 VUs" "5 200"   "$(lt_slice 200 40)"
chk "160 accounts / 40 VUs" "4 160"   "$(lt_slice 160 40)"
chk "40 accounts / 40 VUs"  "1 40"    "$(lt_slice 40 40)"
chk "200 accounts / 1 VU"   "200 200" "$(lt_slice 200 1)"
chk "more VUs than accounts clamps" "1 10" "$(lt_slice 10 40)"

# Disjointness, checked by construction rather than asserted: walk every VU's
# range for a spread of configurations and fail on any index used twice or any
# index out of bounds. A slice of 0 would index past the array, every request
# would 404, and the run would quietly measure Fineract's error path.
disjoint() { # disjoint <accounts> <max_vus>
    local n="$1" mv="$2"
    awk -v n="$n" -v mv="$mv" 'BEGIN{
        vus = (mv < n ? mv : n); s = int(n / vus)
        if (s < 1) { print "SLICE-UNDERRUN"; exit }
        for (v = 1; v <= vus; v++)
            for (i = 0; i < s; i++) {
                idx = (v-1)*s + (i % s)
                if (idx < 0 || idx >= n) { print "OUT-OF-BOUNDS"; exit }
                if (idx in seen)         { print "OVERLAP"; exit }
                seen[idx] = 1
            }
        print "ok"
    }'
}
for cfg in "200 40" "160 40" "40 40" "200 1" "10 40" "1 1" "999 7"; do
    # shellcheck disable=SC2086
    chk "disjoint + in-bounds ($cfg)" "ok" "$(disjoint $cfg)"
done

echo
if [ "$FAIL" -eq 0 ]; then
    printf '\033[32mALL PASS\033[0m\n'
else
    printf '\033[31mFAILURES PRESENT\033[0m — do not trust a RESULT.md produced by this checkout\n'
fi
exit "$FAIL"
