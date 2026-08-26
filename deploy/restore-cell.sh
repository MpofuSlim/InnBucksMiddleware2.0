#!/usr/bin/env bash
# Roll a cell back to a backup taken by deploy/backup-cell.sh.
#
# THIS DESTROYS THE CELL'S CURRENT DATA. It replaces the Postgres volumes
# wholesale. Read the whole script before running it the first time — ideally
# rehearse it on a throwaway cell, because the moment you need it is the worst
# moment to be reading it.
#
# Uses the VOLUME TARBALLS, not the SQL dumps: restoring a volume is a file
# copy, restoring a dump is a replay of every statement, and after a load test
# the dump is the slow one by a wide margin. The dumps in the same directory
# are the fallback for when the Postgres major version has changed underneath
# you (a volume from PG18 will not start on PG16), and for reading with your
# eyes.
#
# Usage: ./deploy/restore-cell.sh /path/to/cell-backups/<STAMP>
set -euo pipefail

SRC="${1:?usage: restore-cell.sh /path/to/cell-backups/<STAMP>}"
[ -d "$SRC" ] || { echo "no such backup directory: $SRC" >&2; exit 1; }

FINERACT_DB_CTR="innbucks-fineract-db"
MIDDLEWARE_DB_CTR="innbucks-middleware-postgres"
ALL_CTRS=("innbucks-fineract" "innbucks-middleware" "$FINERACT_DB_CTR" "$MIDDLEWARE_DB_CTR")

say() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
die() { printf '\n\033[31mFAILED: %s\033[0m\n' "$*" >&2; exit 1; }

say "Backup being restored"
cat "$SRC/MANIFEST.txt" 2>/dev/null || die "$SRC has no MANIFEST.txt — is it a backup-cell.sh directory?"

# --- Major-version guard ----------------------------------------------------
# A PG18 data directory will not start under PG16 and vice versa. Catch it here
# rather than as a container that crash-loops with a cryptic message.
say "Checking Postgres major versions still match"
check_pg_major() {
    local ctr="$1"
    local now was
    now=$(docker exec "$ctr" postgres --version 2>/dev/null | grep -oE '[0-9]+' | head -1 || echo "")
    was=$(grep -oE 'postgres \(PostgreSQL\) [0-9]+' "$SRC/MANIFEST.txt" | grep -oE '[0-9]+$' | sed -n "$2p" || echo "")
    if [ -n "$now" ] && [ -n "$was" ] && [ "$now" != "$was" ]; then
        die "$ctr runs PG$now but the backup is from PG$was. Volume restore will NOT work.
     Use the SQL dump instead:
       gzip -dc $SRC/$(basename "$ctr" | grep -q fineract && echo fineract || echo middleware)-cluster.sql.gz \\
         | docker exec -i $ctr psql -U postgres"
    fi
    echo "  $ctr: PG${now:-unknown} (backup: PG${was:-unknown})"
}
check_pg_major "$FINERACT_DB_CTR" 1
check_pg_major "$MIDDLEWARE_DB_CTR" 2

# --- Confirmation gate ------------------------------------------------------
say "About to DESTROY current cell data"
echo "Current row counts (these are what you are throwing away):"
docker exec "$FINERACT_DB_CTR" psql -U fineract -d fineract_default -At -c \
    "SELECT 'm_savings_account_transaction', count(*) FROM m_savings_account_transaction;" 2>/dev/null || true
docker exec "$MIDDLEWARE_DB_CTR" psql -U "$(docker exec "$MIDDLEWARE_DB_CTR" printenv POSTGRES_USER 2>/dev/null || echo innbucks)" \
    -d innbucks -At -c "SELECT 'ledger_transaction', count(*) FROM ledger_transaction;" 2>/dev/null || true
echo
echo "Restoring from: $SRC"
printf 'Type exactly RESTORE to proceed: '
read -r CONFIRM
[ "$CONFIRM" = "RESTORE" ] || { echo "aborted"; exit 1; }

# --- Stop everything --------------------------------------------------------
say "Stopping the cell"
for ctr in "${ALL_CTRS[@]}"; do
    docker stop "$ctr" >/dev/null 2>&1 && echo "  stopped $ctr" || true
done

# --- Replace volume contents ------------------------------------------------
say "Replacing volume contents"
restore_volume() {
    local tarball="$1"
    # Tarballs are named <label>-<volumename>.tar.gz; recover the volume name
    # by stripping the label, which is everything up to the first '-innbucks'.
    local base vol
    base="$(basename "$tarball" .tar.gz)"
    vol="${base#*-}"
    while ! docker volume inspect "$vol" >/dev/null 2>&1; do
        # Label may contain dashes; keep stripping leading segments.
        local next="${vol#*-}"
        [ "$next" = "$vol" ] && break
        vol="$next"
    done
    docker volume inspect "$vol" >/dev/null 2>&1 || { echo "  SKIP $base (no matching volume)"; return; }
    echo "  $vol <- $(basename "$tarball")"
    docker run --rm -v "$vol":/to -v "$SRC":/from:ro alpine:3 \
        sh -c "rm -rf /to/* /to/..?* /to/.[!.]* 2>/dev/null; tar xzf /from/$(basename "$tarball") -C /to"
}
shopt -s nullglob
for t in "$SRC"/*-innbucks-*.tar.gz; do restore_volume "$t"; done
shopt -u nullglob

# --- Start and verify -------------------------------------------------------
say "Starting the cell"
docker start "$FINERACT_DB_CTR" "$MIDDLEWARE_DB_CTR" >/dev/null
echo "  databases starting; waiting for health"
for i in $(seq 1 60); do
    if docker exec "$FINERACT_DB_CTR" pg_isready -U fineract -d postgres >/dev/null 2>&1 \
       && docker exec "$MIDDLEWARE_DB_CTR" pg_isready >/dev/null 2>&1; then
        echo "  both databases accepting connections"; break
    fi
    sleep 2
    [ "$i" = 60 ] && die "databases did not come up — check: docker logs $FINERACT_DB_CTR"
done
docker start innbucks-fineract innbucks-middleware >/dev/null 2>&1 || true

say "Row counts after restore (compare with row-counts-before.txt)"
MW_USER="$(docker exec "$MIDDLEWARE_DB_CTR" printenv POSTGRES_USER 2>/dev/null || echo innbucks)"
{
    echo "# fineract_default"
    docker exec "$FINERACT_DB_CTR" psql -U fineract -d fineract_default -At -c "
        SELECT 'm_client', count(*) FROM m_client
        UNION ALL SELECT 'm_savings_account', count(*) FROM m_savings_account
        UNION ALL SELECT 'm_savings_account_transaction', count(*) FROM m_savings_account_transaction
        UNION ALL SELECT 'm_portfolio_command_source', count(*) FROM m_portfolio_command_source;"
    echo "# middleware innbucks"
    docker exec "$MIDDLEWARE_DB_CTR" psql -U "$MW_USER" -d innbucks -At -c "
        SELECT 'customer', count(*) FROM customer
        UNION ALL SELECT 'ledger_transaction', count(*) FROM ledger_transaction
        UNION ALL SELECT 'audit_event', count(*) FROM audit_event;"
} | tee /tmp/row-counts-after.txt

echo
if [ -f "$SRC/row-counts-before.txt" ]; then
    say "Diff vs the backup's baseline (empty = clean restore)"
    diff "$SRC/row-counts-before.txt" /tmp/row-counts-after.txt && echo "  identical — restore verified"
fi

cat <<'EOF'

NOTE: the middleware's audit chain seals each row against its predecessor. A
restore rewinds that chain to a consistent earlier point, which is fine — but
the nightly AuditIntegrityVerifier compares against what it last saw, so expect
one chain-break ticket after a restore. That alert is EXPECTED here and is not
evidence of tampering.
EOF
