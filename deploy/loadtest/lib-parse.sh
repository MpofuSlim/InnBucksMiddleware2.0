#!/usr/bin/env bash
# Log parsers for 20-run.sh, extracted so selftest.sh can exercise THE REAL
# CODE rather than a copy of it.
#
# They live here because every one of them has already shipped broken once, and
# each failure was silent — a wrong number in a table cell, never an error:
#
#   * lt_peak_cpu    leaked a digit out of the CONTAINER NAME into the CPU
#                    figure, giving the generator's CPU a hard floor of 6%.
#   * lt_pool_scan   matched the tenant OLTP metric name as a substring of
#                    itself and of the tenants-store metric, mixing a
#                    40-connection pool with a 10-connection one.
#   * lt_gc_secs     split Prometheus lines on whitespace, which separates a
#                    metric name from its value because label VALUES contain
#                    spaces (cause="G1 Evacuation Pause"). Reported 0.00s.
#
# The shared lesson, and the rule for anything added here: parse Prometheus by
# REGEX over the whole line, never by field position, and never re-extract a
# number from a string that still contains its own key.
#
# Every function must DEGRADE to empty or "?" on a missing/empty file rather
# than fail — they run under `set -euo pipefail` after the load has already
# been generated, where a non-zero exit destroys the report the run exists to
# produce. selftest.sh covers that path explicitly.

# lt_peak_cpu <docker-stats.log> <container-name> -> highest CPU%, or empty
#
# docker-stats lines are "HH:MM:SS name=CPU%/MEM% name=CPU%/MEM% ...".
# The leading (^| ) anchors to a whole field so a name cannot match mid-token,
# and the '=' after the name is what keeps 'innbucks-fineract' from also
# matching 'innbucks-fineract-db'. Strip the key with sed rather than a second
# grep for digits — that is what leaked the "6" out of "k6-loadtest".
lt_peak_cpu() {
    local log="$1" name="$2"
    [ -f "$log" ] || return 0
    grep -oE "(^| )${name}=[0-9.]+%" "$log" 2>/dev/null \
        | sed -E 's/.*=([0-9.]+)%$/\1/' | sort -rn | head -1 || true
}

# The tenant OLTP pool — the money path. NOT the tenants-store pool, which is a
# separate 10-connection Hikari serving one @Cacheable SELECT and is never the
# constraint. Anchoring on this prefix is what keeps the two apart.
LT_OLTP_RE='fineract_tenants_[a-z0-9_]+_hikaricp_connections'

# lt_pool_label <fineract-metrics.log> -> the OLTP pool's name, or empty
lt_pool_label() {
    local log="$1"
    [ -f "$log" ] || return 0
    grep -oE "${LT_OLTP_RE}_active\{[^}]*pool=\"[^\"]+\"" "$log" 2>/dev/null \
        | sed -E 's/.*pool="([^"]+)".*/\1/' | sort -u | head -1 || true
}

# lt_pool_scan <fineract-metrics.log>
#   -> "<peak_active> <peak_pending> <samples_with_pending> <total_samples>"
#      with "?" for either peak when nothing was captured.
#
# The third number is the one that settles POOL-BOUND. Peak active and peak
# pending are independent maxima over the whole run and need never have
# co-occurred, so "40 active, 14 pending" from bare maxima is not evidence of a
# queue; "pending in 3 of 126 samples" is.
lt_pool_scan() {
    local log="$1"
    [ -f "$log" ] || { printf '? ? 0 0'; return 0; }
    awk -v re="$LT_OLTP_RE" '
        function best(line, metric,   m, b) {
            b = -1
            while (match(line, re "_" metric "\\{[^}]*\\} [0-9.eE+]+")) {
                m = substr(line, RSTART, RLENGTH); sub(/.*\} /, "", m)
                if (m + 0 > b) b = m + 0
                line = substr(line, RSTART + RLENGTH)
            }
            return b
        }
        BEGIN { pa = -1; pp = -1 }
        NF {
            n++
            a = best($0, "active");  if (a > pa) pa = a
            p = best($0, "pending"); if (p > pp) pp = p
            if (p > 0) ps++
        }
        END { printf "%s %s %d %d", (pa < 0 ? "?" : pa), (pp < 0 ? "?" : pp), ps + 0, n + 0 }
    ' "$log" 2>/dev/null || printf '? ? 0 0'
}

# lt_gc_secs <fineract-metrics.log> -> GC seconds spent during the run, or empty
#
# jvm_gc_pause_seconds_sum is CUMULATIVE and Prometheus emits one series per GC
# cause, so the answer is (sum of all causes on the last sample) minus (the same
# on the first). Walk by regex: a whitespace split lands the metric name and its
# number in non-adjacent fields, which silently yields 0.00.
lt_gc_secs() {
    local log="$1"
    [ -f "$log" ] || return 0
    awk '
        { line = $0; t = ""
          while (match(line, /jvm_gc_pause_seconds_sum\{[^}]*\} [0-9.eE+-]+/)) {
              m = substr(line, RSTART, RLENGTH); sub(/.*\} /, "", m); t += m + 0
              line = substr(line, RSTART + RLENGTH)
          }
          if (t != "") { if (first == "") first = t; last = t } }
        END { if (first != "") printf "%.2f", last - first }
    ' "$log" 2>/dev/null || true
}

# lt_k6_metric <k6-summary.json> <metric> <field> -> value, or empty
# Degrades to empty when the file or jq is missing; jq is only a hard
# requirement of 10-fixtures.sh, not of a run.
lt_k6_metric() {
    local summary="$1" metric="$2" field="$3"
    [ -f "$summary" ] || return 0
    command -v jq >/dev/null 2>&1 || return 0
    jq -r --arg m "$metric" --arg f "$field" '.metrics[$m][$f] // empty' \
        "$summary" 2>/dev/null || true
}

# lt_num2u <value> [unit] -> "12.34unit", or a bare "?" when there is no value.
# Never "?ms" — that reads like a measurement.
lt_num2u() {
    if [ -n "${1:-}" ]; then printf '%.2f%s' "$1" "${2:-}"; else printf '?'; fi
}

# lt_slice <accounts> <max_vus> -> "<slice_per_vu> <accounts_touched>"
#
# MUST stay identical to the arithmetic in deposit-load.js. VU n takes the
# half-open range [(n-1)*slice, n*slice), so slices are disjoint and the
# distinct-account rule (no two VUs on one account, no optimistic-lock
# collisions) still holds exactly.
lt_slice() {
    local accounts="$1" max_vus="$2" vus slice
    vus=$(( max_vus < accounts ? max_vus : accounts ))
    [ "$vus" -gt 0 ] || { printf '0 0'; return 0; }
    slice=$(( accounts / vus ))
    printf '%d %d' "$slice" "$(( vus * slice ))"
}
