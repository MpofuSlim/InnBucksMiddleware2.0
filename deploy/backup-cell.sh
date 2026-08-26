#!/usr/bin/env bash
# Point-in-time backup of a whole InnBucks cell, for taking BEFORE anything
# destructive — a load test, a Fineract upgrade, a schema migration.
#
# Takes two kinds of backup on purpose, because they fail differently:
#
#   1. SQL dumps (pg_dumpall per cluster). Portable, greppable, restorable into
#      a different Postgres, and verifiable by eye. Slow to restore.
#   2. Volume tarballs. Byte-for-byte, restore is "stop, replace, start", which
#      is what you actually want to roll a cell back in a hurry. Only restorable
#      into the SAME Postgres major version.
#
# Also copies the un-gitignorable bits — .env files and the TLS material — whose
# loss is more annoying than the data's.
#
# The app containers are STOPPED for the dump. A pg_dumpall runs a separate
# dump per database, so with Fineract writing you could capture fineract_tenants
# and fineract_default a second apart and restore an inconsistent pair. Stopping
# costs a minute and removes the question.
#
# Usage:   ./deploy/backup-cell.sh [/path/to/backup/root]
# Default: ~/cell-backups/<UTC timestamp>
set -euo pipefail

BACKUP_ROOT="${1:-$HOME/cell-backups}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
DEST="$BACKUP_ROOT/$STAMP"

FINERACT_DB_CTR="innbucks-fineract-db"
MIDDLEWARE_DB_CTR="innbucks-middleware-postgres"
APP_CTRS=("innbucks-fineract" "innbucks-middleware")

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

say() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
die() { printf '\n\033[31mFAILED: %s\033[0m\n' "$*" >&2; exit 1; }

# --- Pre-flight -------------------------------------------------------------
say "Pre-flight"

for ctr in "$FINERACT_DB_CTR" "$MIDDLEWARE_DB_CTR"; do
    docker inspect "$ctr" >/dev/null 2>&1 || die "container $ctr not found — is the cell up?"
done

# A backup that fills the disk is worse than no backup: it takes the cell down
# too. Require headroom of 3x the current volume usage.
USED_KB=$(docker system df -v --format '{{json .}}' 2>/dev/null \
    | python3 -c "
import json,sys
try:
    d=json.load(sys.stdin)
except Exception:
    print(0); raise SystemExit
tot=0
for v in d.get('Volumes',[]):
    n=v.get('Name','')
    if 'pgdata' in n or 'fineract-content' in n:
        s=v.get('Size','0B')
        mult={'B':1,'kB':1e3,'KB':1e3,'MB':1e6,'GB':1e9,'TB':1e12}
        for u,m in sorted(mult.items(), key=lambda x:-len(x[0])):
            if s.endswith(u):
                tot += float(s[:-len(u)] or 0)*m/1000
                break
print(int(tot))
" 2>/dev/null || echo 0)
AVAIL_KB=$(df -Pk "$BACKUP_ROOT" 2>/dev/null | awk 'NR==2{print $4}' \
    || df -Pk "$HOME" | awk 'NR==2{print $4}')
echo "cell data ~${USED_KB}KB, free at backup target ~${AVAIL_KB}KB"
if [ "${USED_KB:-0}" -gt 0 ] && [ "$AVAIL_KB" -lt $(( USED_KB * 3 )) ]; then
    die "not enough free space: want 3x the data size. Free some, or pass a path on a bigger disk."
fi

mkdir -p "$DEST"
echo "backing up to $DEST"

# --- Record what we are backing up FROM -------------------------------------
say "Recording cell state"
{
    echo "taken_utc=$STAMP"
    echo "host=$(hostname)"
    echo "repo_commit=$(git -C "$REPO_DIR" rev-parse HEAD 2>/dev/null || echo unknown)"
    echo
    echo "--- running images ---"
    docker ps --format '{{.Names}}\t{{.Image}}' 2>/dev/null
    echo
    echo "--- postgres versions ---"
    docker exec "$FINERACT_DB_CTR" postgres --version 2>/dev/null || true
    docker exec "$MIDDLEWARE_DB_CTR" postgres --version 2>/dev/null || true
} > "$DEST/MANIFEST.txt"
cat "$DEST/MANIFEST.txt"

# --- Quiesce ----------------------------------------------------------------
say "Stopping app containers (databases stay up for the dump)"
STOPPED=()
for ctr in "${APP_CTRS[@]}"; do
    if docker inspect -f '{{.State.Running}}' "$ctr" 2>/dev/null | grep -q true; then
        docker stop "$ctr" >/dev/null && STOPPED+=("$ctr")
        echo "stopped $ctr"
    fi
done

restart_apps() {
    if [ ${#STOPPED[@]} -gt 0 ]; then
        say "Restarting app containers"
        for ctr in "${STOPPED[@]}"; do docker start "$ctr" >/dev/null && echo "started $ctr"; done
    fi
}
# Whatever happens below, the cell comes back up.
trap restart_apps EXIT

# --- SQL dumps --------------------------------------------------------------
say "SQL dumps (pg_dumpall per cluster)"

docker exec "$FINERACT_DB_CTR" pg_dumpall -U fineract \
    | gzip > "$DEST/fineract-cluster.sql.gz"
echo "fineract-cluster.sql.gz  $(du -h "$DEST/fineract-cluster.sql.gz" | cut -f1)"

MW_USER="$(docker exec "$MIDDLEWARE_DB_CTR" printenv POSTGRES_USER 2>/dev/null || echo innbucks)"
docker exec "$MIDDLEWARE_DB_CTR" pg_dumpall -U "$MW_USER" \
    | gzip > "$DEST/middleware-cluster.sql.gz"
echo "middleware-cluster.sql.gz  $(du -h "$DEST/middleware-cluster.sql.gz" | cut -f1)"

# --- Volume tarballs --------------------------------------------------------
say "Volume tarballs"
# Discover volume names from the containers rather than guessing the compose
# project prefix.
backup_volumes_of() {
    local ctr="$1" label="$2"
    docker inspect -f '{{range .Mounts}}{{if eq .Type "volume"}}{{.Name}} {{.Destination}}{{"\n"}}{{end}}{{end}}' "$ctr" \
    | while read -r vol dest; do
        [ -z "$vol" ] && continue
        echo "  $label: $vol ($dest)"
        docker run --rm \
            -v "$vol":/from:ro \
            -v "$DEST":/to \
            alpine:3 tar czf "/to/${label}-${vol}.tar.gz" -C /from . 2>/dev/null
    done
}
backup_volumes_of "$FINERACT_DB_CTR" fineract-db
backup_volumes_of "$MIDDLEWARE_DB_CTR" middleware-db
for ctr in "${APP_CTRS[@]}"; do backup_volumes_of "$ctr" "$(basename "$ctr")"; done

# --- Config that is not in git ----------------------------------------------
say "Config not in git"
mkdir -p "$DEST/config"
for f in "$REPO_DIR/.env" "$REPO_DIR/deploy/fineract/.env"; do
    [ -f "$f" ] && cp "$f" "$DEST/config/$(echo "${f#$REPO_DIR/}" | tr / _)" && echo "  $f"
done
if [ -d "$REPO_DIR/deploy/fineract/ssl" ]; then
    tar czf "$DEST/config/fineract-ssl.tar.gz" -C "$REPO_DIR/deploy/fineract" ssl && echo "  deploy/fineract/ssl"
fi
chmod -R go-rwx "$DEST/config"

# --- Verify -----------------------------------------------------------------
# An unverified backup is a hope. These checks are cheap and catch the two
# failure modes that actually happen: a dump that is empty/truncated because
# the container was wedged, and a dump that succeeded but of the wrong cluster.
say "Verifying"
verify_dump() {
    local file="$1" must_contain="$2"
    gzip -t "$file" || die "$file is not a valid gzip — dump truncated"
    local bytes
    bytes=$(gzip -dc "$file" | wc -c)
    [ "$bytes" -gt 10000 ] || die "$file decompresses to only ${bytes}B — almost certainly empty"
    gzip -dc "$file" | grep -q "$must_contain" \
        || die "$file does not mention '$must_contain' — wrong cluster or incomplete dump"
    echo "  OK $(basename "$file")  ${bytes}B raw, contains '$must_contain'"
}
verify_dump "$DEST/fineract-cluster.sql.gz"   "m_savings_account"
verify_dump "$DEST/middleware-cluster.sql.gz" "ledger_transaction"

for t in "$DEST"/*.tar.gz; do
    [ -e "$t" ] || continue
    tar tzf "$t" >/dev/null || die "$t is not a readable tarball"
    echo "  OK $(basename "$t")  $(du -h "$t" | cut -f1)"
done

# --- Row counts, as the thing you compare against afterwards ----------------
say "Baseline row counts (compare after the test)"
{
    echo "# fineract_default"
    docker exec "$FINERACT_DB_CTR" psql -U fineract -d fineract_default -At -c "
        SELECT 'm_client', count(*) FROM m_client
        UNION ALL SELECT 'm_savings_account', count(*) FROM m_savings_account
        UNION ALL SELECT 'm_savings_account_transaction', count(*) FROM m_savings_account_transaction
        UNION ALL SELECT 'm_portfolio_command_source', count(*) FROM m_portfolio_command_source;" 2>/dev/null
    echo "# middleware innbucks"
    docker exec "$MIDDLEWARE_DB_CTR" psql -U "$MW_USER" -d innbucks -At -c "
        SELECT 'customer', count(*) FROM customer
        UNION ALL SELECT 'ledger_transaction', count(*) FROM ledger_transaction
        UNION ALL SELECT 'audit_event', count(*) FROM audit_event;" 2>/dev/null
} | tee "$DEST/row-counts-before.txt"

say "Done — $DEST"
du -sh "$DEST"
cat <<EOF

NEXT:
  1. Copy this OFF the box. A backup that only exists on the machine you are
     about to stress is not a backup:
         scp -r <this box>:$DEST ./
     (or: aws s3 cp --recursive "$DEST" s3://<your-bucket>/cell-backups/$STAMP/)
  2. Restore procedure is in deploy/restore-cell.sh — read it BEFORE you need it.
EOF
