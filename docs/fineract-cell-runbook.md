# Fineract cell runbook

Standing up (or upgrading) the Fineract half of an InnBucks cell. First cell:
budget a day. Later cells: about an hour with this runbook. Everything here
assumes the box already runs Docker + compose and you can SSH to it.

The layout on the box:

```
~/InnBucksMiddleware2.0/            # this repo (middleware stack, root compose)
~/InnBucksMiddleware2.0/deploy/fineract/   # the Fineract stack (this kit)
```

## 0. One-time host setup

```sh
# The private network both stacks share — the ONLY path to Fineract's 8443.
docker network create innbucks-cell-shared
```

## 1. Pin + build the image (repo: MpofuSlim/fineract)

Upgrades are deliberate: pick the commit, tag it, let the Release workflow
build → Trivy-scan → push → attest. Never deploy a local `develop` build.

```sh
git -C fineract tag innbucks-cell-1 <commit>
git -C fineract push origin innbucks-cell-1
```

Watch the **Release InnBucks cell image** workflow. A red Trivy gate means a
fixable CRITICAL/HIGH CVE exists — upgrade it, or add a governed waiver to
`.trivyignore` (owner + reason + review date; rules in the file). On green,
the image exists as `ghcr.io/mpofuslim/fineract:innbucks-cell-1` and
`:sha-<commit>`, verifiable with:

```sh
gh attestation verify oci://ghcr.io/mpofuslim/fineract@<digest> --repo MpofuSlim/fineract
```

## 2. TLS: the cell CA and Fineract's keystore

One tiny internal CA per cell; Fineract serves its cert, the middleware
trusts the CA. Run in `deploy/fineract/ssl/` (gitignored — keys never leave
the box):

```sh
mkdir -p ssl && cd ssl
# Cell CA (10y) — the ONLY thing the middleware needs to trust.
openssl req -x509 -newkey rsa:4096 -sha256 -days 3650 -nodes \
  -keyout cell-ca.key -out cell-ca.crt -subj "/CN=InnBucks Cell CA"
# Fineract server cert, SAN = the compose network alias "fineract".
openssl req -newkey rsa:2048 -nodes -keyout fineract.key -out fineract.csr \
  -subj "/CN=fineract"
openssl x509 -req -in fineract.csr -CA cell-ca.crt -CAkey cell-ca.key \
  -CAcreateserial -days 825 -sha256 -out fineract.crt \
  -extfile <(printf "subjectAltName=DNS:fineract,DNS:localhost")
# Keystore for Fineract (password goes to FINERACT_KEYSTORE_PASSWORD in .env):
openssl pkcs12 -export -in fineract.crt -inkey fineract.key \
  -name fineract -out fineract-keystore.p12
# REQUIRED: openssl writes this 0600 owned by you, but the Fineract image runs
# as `nobody:nogroup` (fineract-provider/build.gradle), so the container can't
# open the mount and Boot dies at startup with
#   Caused by: java.nio.file.AccessDeniedException: /ssl/fineract-keystore.p12
# Safe: the P12 is password-protected and that password lives in .env at 0600,
# so a readable keystore alone yields nothing.
chmod 644 fineract-keystore.p12
# Truststore for the middleware (containing ONLY the CA):
keytool -importcert -noprompt -file cell-ca.crt -alias innbucks-cell-ca \
  -keystore innbucks-cell-truststore.p12 -storetype PKCS12
```

No JDK on the box? Run `keytool` from a throwaway container instead, then take
ownership of what it wrote as root:

```sh
docker run --rm -v "$PWD":/w -w /w eclipse-temurin:21-jre \
  keytool -importcert -noprompt -file cell-ca.crt -alias innbucks-cell-ca \
  -keystore innbucks-cell-truststore.p12 -storetype PKCS12 -storepass '<pw>'
sudo chown "$(id -u):$(id -g)" innbucks-cell-truststore.p12
```

Wire the truststore into the **middleware** service (root
`docker-compose.yml`): mount it and extend `JAVA_TOOL_OPTIONS`:

```yaml
    volumes:
      - ./deploy/fineract/ssl/innbucks-cell-truststore.p12:/ssl/truststore.p12:ro
    environment:
      JAVA_TOOL_OPTIONS: >-
        -XX:MaxRAMPercentage=70.0
        -Djavax.net.ssl.trustStore=/ssl/truststore.p12
        -Djavax.net.ssl.trustStorePassword=<truststore password>
```

Never set the client to skip TLS verification instead.

## 3. Boot the Fineract stack

```sh
cd deploy/fineract
cp .env.example .env          # fill: image tag + three generated secrets
docker compose pull && docker compose up -d
docker compose logs -f fineract   # first boot runs all tenant migrations — minutes
```

8443 is loopback-only on the host. For the admin UI / provisioning from your
laptop: `ssh -L 8443:127.0.0.1:8443 <box>`.

## 4. Provision the tenant

```sh
cd deploy/fineract
export ADMIN_PASSWORD=password                  # the stock default…
export ROTATE_ADMIN_PASSWORD="$(./provision-cell.sh --gen-password)"   # …rotated first thing
export MW_READ_PASSWORD="$(./provision-cell.sh --gen-password)"
export MW_WRITE_PASSWORD="$(./provision-cell.sh --gen-password)"
export CELL_CURRENCY=USD
export CURL_OPTS="--cacert ssl/cell-ca.crt"
RUN_SMOKE=1 ./provision-cell.sh
```

Use `--gen-password`, not `openssl rand -base64 24`: Fineract's active
`strong` policy forbids **consecutive repeated characters**, which base64
output violates about 40% of the time. The script also pre-checks all three
passwords against the cell's active policy before its first write, so a bad
one fails immediately instead of half-way through the run.

The script is idempotent (re-run safely, including after the admin password
has already been rotated — it detects that and skips step 2). It waits for
Fineract, rotates the admin password, allows the cell currency, creates the
zero-interest wallet savings product, creates the two least-privilege roles +
AppUsers (verifying every permission code exists on this build first — never
`ALL_FUNCTIONS`), and with `RUN_SMOKE=1` drives the adapter's exact call
sequence (client → wallet create/approve/activate → deposit → read-back by
external id) using the new middleware credentials. It prints the values the
middleware's `.env` needs. **Store the rotated admin password in your
password manager** — it is the break-glass credential for this cell.

If step 1 reports that Fineract rejected the credentials, that is a password
problem and waiting will not fix it — on a re-run, `ADMIN_PASSWORD` must be
the value you rotated *to* (or leave `ROTATE_ADMIN_PASSWORD` exported at that
same value and the script will work it out).

### The logical business date (bites every cell restored from a dump)

Fineract dates writes by its **logical business date**, not the wall clock,
whenever the `enable_business_date` configuration is on. Nothing advances that
date while the stack is down, so a cell restored from an older dump comes up
with a stale one — and then *every* write dated today is rejected with

```
Activation date cannot be in the future.  {"parameterName":"activationDate","args":[{"value":"<today>"}]}
```

which names the date you **sent**, never the stale one it compared against.
The middleware hits this too, not just the smoke: `FineractClient` stamps
`activationDate`/`submittedOnDate`/`transactionDate` from the real clock, so
`POST /register` and every deposit fail the same way.

`provision-cell.sh` now checks this at startup and fails with the fix. For a
savings-only wallet cell, **turn the feature off** — Fineract then uses the
tenant's own date and nothing has to advance it daily:

```sh
API=https://localhost:8443/fineract-provider/api
AUTH=(-u "mifos:$ADMIN_PASSWORD" -H "Fineract-Platform-TenantId: default")

ID=$(curl -sS $CURL_OPTS "${AUTH[@]}" "$API/v1/configurations" \
  | jq -r '[.. | objects | select(.name? == "enable_business_date")] | .[0].id')
curl -sS $CURL_OPTS -X PUT "${AUTH[@]}" -H 'Content-Type: application/json' \
  -d '{"enabled":false}' "$API/v1/configurations/$ID"
```

Resolve the id off the list endpoint rather than using
`/v1/configurations/name/enable_business_date` — the by-name **GET** does not
return `name`/`enabled` at the top level, so it reads as `null` and quietly
misleads you into thinking the feature is off.

Keep it on only if the cell deliberately runs COB batch processing — in which
case something must advance the date daily, or this recurs tomorrow.

While you are there, check the tenant timezone, which the compose file can only
set when it *creates* the tenant row — a restored `fineract_tenants` keeps
whatever the old stack used (the upstream sample ships `Asia/Kolkata`):

```sh
docker compose exec -T fineract-db psql -U fineract -d fineract_tenants \
  -c "SELECT identifier, timezone_id FROM tenants;"
# expect UTC; if not:
docker compose exec -T fineract-db psql -U fineract -d fineract_tenants \
  -c "UPDATE tenants SET timezone_id='UTC' WHERE identifier='default';"
docker compose restart fineract
```

A non-UTC tenant puts every Fineract-side date out of step with the
middleware, which is UTC everywhere by construction.

## 5. Wire and start the middleware

In the repo root: `.env` gets the `FINERACT_*` values the script printed plus
the five middleware secrets (`openssl rand -base64 48` each, all distinct),
`INNBUCKS_COUNTRY`, `INNBUCKS_CORE_PROVIDER=fineract`, and
`FINERACT_BASE_URL=https://fineract:8443/fineract-provider/api`. Then:

```sh
docker compose up -d
```

End-to-end smoke, through the middleware:

1. `POST /register` (Idempotency-Key header) → 201 with the wallet id.
2. OTP: with `NOTIFY_PROVIDER=innbucks-gateway` (+ the four `NOTIFY_API_*`
   values from the platform team) the code arrives by real SMS — optionally
   with `WHATSAPP_FALLBACK_ENABLED=true` (+ `WHATSAPP_*`) as the fallback
   channel. On the `console` default, codes only appear in the middleware
   logs — fine for UAT, and the app logs a page-worthy ERROR at boot to stop
   that reaching go-live.
3. `POST /auth/pin/set` → `POST /auth/login` → Bearer token.
4. `GET /me/accounts` → the wallet with balance 0.
5. `POST /transactions/deposit` then `/transfer` — watch
   `ledger_transaction` land COMPLETED and, for anything parked,
   `innbucks.ledger.parked_overdue` stay at zero.

## 6. Upgrade / rollback

- **Upgrade**: tag a new commit `innbucks-cell-2`, let the Release workflow
  go green, set `FINERACT_IMAGE_TAG=innbucks-cell-2`, `docker compose up -d`.
  Read the fork's migration notes between the two commits first — Fineract
  runs schema migrations forward automatically; there is no down-migration.
- **Rollback (image only)**: set `FINERACT_IMAGE_TAG` back to the previous
  `sha-<commit>` tag and `docker compose up -d` — safe ONLY if the newer
  version didn't migrate the schema past the old one; otherwise restore the
  Postgres volume from backup. Which is the reminder to
  **back up `innbucks-fineract-pgdata` before every upgrade.**

## Security invariants for this stack

- No public 8443, ever — loopback publish + the shared docker network only.
- Every credential in `.env`, rotated from defaults; the tenant-store master
  password encrypts tenant creds at rest.
- The middleware rides two least-privilege AppUsers; the admin account is
  break-glass only.
- The image is pinned + scanned + attested; upgrades are deliberate tags.
