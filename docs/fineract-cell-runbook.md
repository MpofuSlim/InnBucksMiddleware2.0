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
```

### The middleware truststore MUST contain the public CAs too

> [!WARNING]
> `-Djavax.net.ssl.trustStore` **replaces** the JVM's default `cacerts`; it does
> not add to it. A truststore holding only the cell CA makes the middleware
> trust Fineract and **nothing else on the internet** — the SMS/notification
> gateway, and any other outbound HTTPS, fail with:
> ```
> PKIX path building failed: unable to find valid certification path to requested target
> ```
> Fineract keeps working the whole time, so this surfaces late — on the first
> outbound call that isn't Fineract. Build the store from the JVM's default
> trust anchors and add the cell CA alongside them.

```sh
cd deploy/fineract/ssl
TRUSTSTORE_PW=<generate: openssl rand -hex 24>

docker run --rm -v "$PWD":/w -w /w -e PW="$TRUSTSTORE_PW" eclipse-temurin:21-jre sh -c '
  set -e
  rm -f /w/ts.p12
  keytool -importkeystore -noprompt \
    -srckeystore "$JAVA_HOME/lib/security/cacerts" -srcstorepass changeit \
    -destkeystore /w/ts.p12 -deststoretype PKCS12 -deststorepass "$PW"
  keytool -importcert -noprompt -file /w/cell-ca.crt -alias innbucks-cell-ca \
    -keystore /w/ts.p12 -storetype PKCS12 -storepass "$PW"
  keytool -list -keystore /w/ts.p12 -storepass "$PW" | grep -c trustedCertEntry
'
sudo chown "$(id -u):$(id -g)" ts.p12
mv -f ts.p12 innbucks-cell-truststore.p12
```

That trailing `-list | grep -c` is the check that matters: expect roughly 150
entries. **`1` means you built the CA-only store and outbound HTTPS will fail.**

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

**Per-market policy lives in `deploy/cells/cell.<iso>.env`, not in your shell
history.** Role grants, the maker-checker task list, control-account glCodes —
anything that should be identical every time this market's cell is stood up —
belongs in that file, which is committed and reviewable as a diff. Secrets stay
in the gitignored `.env`. Source both, then run the script:

```sh
set -a; source ../cells/cell.zw.env; set +a     # policy, committed, NON-SECRET
set -a; source .env;                 set +a     # secrets, gitignored
./provision-cell.sh
```

Everything the script does is idempotent and **additive** — a re-run re-asserts
the cell file's intent and never revokes a grant the file does not name, because
stripping a permission an operator added deliberately is worse than leaving one
behind. See `deploy/cells/cell.example.env` for what each key means.

The block below is the equivalent done by hand, and still the right shape for a
first stand-up where you are generating the passwords as you go:

```sh
cd deploy/fineract
export ADMIN_PASSWORD=password                  # the stock default…
export ROTATE_ADMIN_PASSWORD="$(./provision-cell.sh --gen-password)"   # …rotated first thing
export MW_READ_PASSWORD="$(./provision-cell.sh --gen-password)"
export MW_WRITE_PASSWORD="$(./provision-cell.sh --gen-password)"
export CELL_CURRENCY=USD
export CURL_OPTS="--cacert ssl/cell-ca.crt"
# Optional but recommended: registers Fineract's Web hook back to the
# middleware so a teller/admin posting still SMSes the customer. Put the SAME
# value in the middleware .env as FINERACT_CORE_EVENTS_TOKEN (it is the
# webhook's auth, carried in the URL over the private cell network).
export CORE_EVENTS_TOKEN="$(openssl rand -hex 32)"
# Optional: the CBS console's Entity ("company") client dropdowns. The defaults
# are ZIMBABWE-shaped — set these per market, or to '' to skip. See below.
# export CLIENT_CONSTITUTIONS="Sole Trader|Partnership|…"
# export CLIENT_BUSINESS_LINES="Agriculture|Mining|…"
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

### Client Constitution / Main Business Line code values (step 4d)

Fineract ships the two **codes** the Entity-client form reads but **zero
values**, and it rejects any `clientNonPersonDetails` block with no
`constitutionId` — so on an unseeded cell the CBS console's Company Details
section cannot be used at all (contract in
[`client-legal-form-api.md`](client-legal-form-api.md)). Step 4d seeds them.

- **The defaults are Zimbabwe-shaped** — `Private Business Corporation` is a
  COBE Act entity type. Set `CLIENT_CONSTITUTIONS` / `CLIENT_BUSINESS_LINES`
  (`|`-separated) for any other market, or `''` to skip and let an admin add
  them under Admin → System → Manage Codes.
- **Get the constitution list signed off before a cell serves real companies.**
  Each value becomes a `m_client_non_person.constitution_cv_id` foreign key, so
  renaming or dropping one afterwards is a migration, not an edit. Seeding is
  additive and never touches values already on the cell.
- **This step is deliberately non-fatal.** The middleware creates only PERSON
  clients, so nothing it does depends on these; a back-office form section must
  not be able to fail a cell stand-up. Failures print `WARN` lines naming the
  cause (missing `CREATE_CODEVALUE`, or maker-checker parking the writes — a
  success-shaped 2xx with no `resourceId`), and re-running the script is the
  fix.

If step 1 reports that Fineract rejected the credentials, that is a password
problem and waiting will not fix it — on a re-run, `ADMIN_PASSWORD` must be
the value you rotated *to* (or leave `ROTATE_ADMIN_PASSWORD` exported at that
same value and the script will work it out).

### Lost the AppUser passwords (break-glass)

Fineract has no way to read a password back, and `provision-cell.sh`
deliberately will not change an existing user's password — so a lost
`MW_WRITE_PASSWORD` means the middleware `.env` cannot be completed. Rather
than hunt for the old values, mint all three fresh. Spring's delegating
encoder stores `{bcrypt}$2b$...`, so the hashes can be written directly.

**Note the two different bcrypt costs below — that split is deliberate**, and
the reasoning is in [AppUser bcrypt cost](#appuser-bcrypt-cost-6-on-the-money-path)
directly after this section. Do not "tidy" them into one value.

```sh
cd deploy/fineract

ADMIN_PW=$(./provision-cell.sh --gen-password)
READ_PW=$(./provision-cell.sh --gen-password)
WRITE_PW=$(./provision-cell.sh --gen-password)
printf '\nSAVE THESE\n  mifos             : %s\n  innbucks-mw-read  : %s\n  innbucks-mw-write : %s\n\n' \
  "$ADMIN_PW" "$READ_PW" "$WRITE_PW"

cat > /tmp/hash.py <<'PYEOF'
import os, bcrypt
# admin is interactive and low-volume -> keep the strong default cost.
# The two middleware users are verified on EVERY money movement -> cost 6.
# See "AppUser bcrypt cost" below before changing either number.
for k, cost in (("A", 10), ("R", 6), ("W", 6)):
    h = bcrypt.hashpw(os.environ[k].encode(), bcrypt.gensalt(cost)).decode()
    print(k + "_HASH='{bcrypt}" + h + "'")
PYEOF

eval "$(docker run --rm -v /tmp/hash.py:/hash.py:ro \
  -e A="$ADMIN_PW" -e R="$READ_PW" -e W="$WRITE_PW" \
  python:3-slim sh -c 'pip install -q bcrypt && python /hash.py')"

docker compose exec -T fineract-db psql -U fineract -d fineract_default -v ON_ERROR_STOP=1 <<SQL
UPDATE m_appuser SET password='${A_HASH}', firsttime_login_remaining=false, password_reset_required=false, nonexpired=true, nonlocked=true, nonexpired_credentials=true, enabled=true WHERE username='mifos';
UPDATE m_appuser SET password='${R_HASH}', firsttime_login_remaining=false, password_reset_required=false, nonexpired=true, nonlocked=true, nonexpired_credentials=true, enabled=true WHERE username='innbucks-mw-read';
UPDATE m_appuser SET password='${W_HASH}', firsttime_login_remaining=false, password_reset_required=false, nonexpired=true, nonlocked=true, nonexpired_credentials=true, enabled=true WHERE username='innbucks-mw-write';
SQL

docker compose restart fineract
```

Verify with an authenticated call. **`401` is the only failure** — the two
middleware users correctly return `403` on `/v1/offices` because they hold no
`READ_OFFICE`; that is least-privilege working, not a broken credential.

The same UPDATE clears `nonlocked`, which matters because Fineract locks an
account after repeated failed logins — retrying a stale password is enough to
lock yourself out, and the symptom looks identical to a wrong password.

### AppUser bcrypt cost (6 on the money path)

**`innbucks-mw-read` and `innbucks-mw-write` are stored at bcrypt cost 6, not
the default 10. `mifos` stays at 10.** This is a deliberate, measured
trade-off; the reasoning matters more than the number.

**Why it is safe here.** bcrypt's work factor exists to make *guessing* a
password expensive — it buys time against an offline attack on a stolen hash of
a LOW-entropy, human-chosen secret. These two are service accounts whose
passwords come from `provision-cell.sh --gen-password`: 20 characters from a
68-character alphabet, about **122 bits of entropy**. At that size the search
space is the defence and the KDF cost is irrelevant — cost 6 and cost 10 are
both "will never be brute-forced." `mifos` keeps cost 10 because it is
interactive, low-volume, and more likely to be given a human-chosen password one
day.

**This argument is conditional, and the condition must be checked.** It holds
only while those passwords really are generated. Before writing a cost-6 hash,
confirm the plaintext is long and varied (prints no secret):

```sh
p=$(grep -E '^FINERACT_WRITE_PASSWORD=' ~/InnBucksMiddleware2.0/.env | cut -d= -f2-)
echo "length=${#p} distinct=$(printf '%s' "$p" | fold -w1 | sort -u | wc -l)"
```

Want length ≥ 16 and distinct ≥ 15. **If someone has set a memorable password,
cost 6 is not acceptable** — regenerate before lowering the cost.

**Why it is worth doing.** `SecurityConfig` (fork) wires a
`DaoAuthenticationProvider` with `PasswordEncoderFactories
.createDelegatingPasswordEncoder()`, and there is **no password-verification
cache on that path** — `matches()` runs on every single request. Every deposit,
every balance read and every ownership check pays the full hash. Measured on the
staging cell (2026-08-27), same endpoint, single-threaded, five samples each:

| | median | mean |
|---|---|---|
| cost 10 | 187ms | 189ms |
| cost 6 | 98ms | 107ms |

**~85ms per authenticated request**, on a box where Fineract was spending ~425ms
of CPU per deposit — so roughly a 25% throughput gain, all of it CPU handed back.

**The trap: this is not durable.** `BCryptPasswordEncoder.matches()` reads the
cost out of the stored hash, so nothing in config pins it. **Any password change
through Fineract's API re-hashes at the delegating encoder's default cost 10 and
silently reverts this** — no error, no log, throughput just quietly drops back.
That includes a `provision-cell.sh` run that rotates these users, and the admin
UI. After any credential rotation, re-apply cost 6 and re-check:

```sh
docker exec innbucks-fineract-db psql -U fineract -d fineract_default -At -c \
  "SELECT username, substring(password from 1 for 15) FROM m_appuser
    WHERE username LIKE 'innbucks-mw-%';"
```

Both rows must read `{bcrypt}$2b$06$`. A `$10$` there means the change was lost.

Apply it the same way the break-glass section does — generate the hash at cost 6
and `UPDATE m_appuser` directly. **Verify with an authenticated call before
walking away: `401` is the only failure**; `403` on `/v1/offices` is
least-privilege working correctly and proves the password verified. Keep the old
hash to hand until you have seen that, because a bad hash locks the middleware
out of the core entirely.

### Maker-checker must not gate the middleware's commands

Fineract's dual-control workflow returns a **success-shaped response** for a
command it parks: HTTP 200 with `{"commandId":N,"rollbackTransaction":true}`
and no `resourceId`, while the transaction is rolled back
(`CommandSourceService.processCommand` → `RollbackTransactionNotApprovedException`).
Nothing was created. Downstream you get a baffling error — the smoke's
`POST /v1/savingsaccounts` complaining that `clientId` is mandatory, because
the client id it was told to use came back empty.

An automated rail cannot satisfy dual control: there is no second human, and
the middleware's service account must not be a checker super user. So the
codes the middleware issues have to be exempt — and only those, leaving every
human-facing permission under maker-checker as before.

```sh
docker compose exec -T fineract-db psql -U fineract -d fineract_default \
  -c "SELECT name, enabled FROM c_configuration WHERE name = 'maker-checker';"
docker compose exec -T fineract-db psql -U fineract -d fineract_default \
  -c "SELECT code, can_maker_checker FROM m_permission
      WHERE code IN ('CREATE_CLIENT','ACTIVATE_CLIENT','CREATE_SAVINGSACCOUNT',
                     'APPROVE_SAVINGSACCOUNT','ACTIVATE_SAVINGSACCOUNT',
                     'DEPOSIT_SAVINGSACCOUNT','WITHDRAWAL_SAVINGSACCOUNT',
                     'CREATE_ACCOUNTTRANSFER');"
```

If the global flag is on and any of those codes has `can_maker_checker = t`:

```sh
docker compose exec -T fineract-db psql -U fineract -d fineract_default \
  -c "UPDATE m_permission SET can_maker_checker = false
      WHERE code IN ('CREATE_CLIENT','ACTIVATE_CLIENT','CREATE_SAVINGSACCOUNT',
                     'APPROVE_SAVINGSACCOUNT','ACTIVATE_SAVINGSACCOUNT',
                     'DEPOSIT_SAVINGSACCOUNT','WITHDRAWAL_SAVINGSACCOUNT',
                     'CREATE_ACCOUNTTRANSFER');"
docker compose restart fineract
```

The column is `can_maker_checker` — not `is_maker_checker`, which is the name
the API and the docs suggest. Note also that `CREATE_CLIENT` alone is not
enough to check: creating a client with `active:true` runs the activate
command inline, so `ACTIVATE_CLIENT` gates it too.

**The middleware now also guards this client-side** (`FineractClient`
refuses a `rollbackTransaction:true` response as `CoreUnknownOutcomeException`,
pinned by the parked-command cases in `FineractClientContractTest`): a
mis-flagged permission can no longer make a parked deposit read as a SUCCESS
— the row parks UNKNOWN, the log screams `Fineract PARKED command … for
maker-checker approval`, and the parked-overdue page fires if it's not fixed.
The rail still stalls until the SQL above runs, so the guard is a tripwire,
not a substitute for the exemption. Never resolve it by approving the parked
commands in bulk from the Checker Inbox — approval RE-EXECUTES them, moving
customer money at approval time, hours after the app told the customer the
movement failed.

### Maker-checker is provisioned, not hand-configured

`provision-cell.sh` steps 6b–6e apply the bank's operational policy from the
cell file, in the one order that is safe:

1. **6b** grant the bank's operational roles (`BANK_ROLE_GRANTS`) — the role
   must ALREADY EXIST (roles belong to the bank; a name matching nothing is
   refused, with the closest existing names printed), and every code is checked
   against the running build first, so a typo warns loudly instead of silently
   producing a role that authenticates fine and is refused everywhere.

   **Get the role names from the cell, never from a test script.** 6b used to
   create a missing role, and on the ZW cell that turned three near-miss names
   ("Test Internal Auditor" vs the real "TEST Internal Audit") into three empty
   roles nobody held — each granted correctly and each reported as success,
   while the auditor still could not read the audit log. A grant to a role with
   no members changes nothing for anyone, so check both:

   ```sh
   api GET /v1/roles | jq -r '.[]|"\(.id)\t\(.name)"'
   api GET /v1/users | jq -r '.[]|"\(.username)\t\([.selectedRoles[]?.name]|join(", "))"'
   ```
2. **6c** make the GL control accounts refuse manual journals
   (`CONTROL_ACCOUNT_GLCODES`, keyed on glCode because ids differ per cell).
3. **6d** warn about loan products whose accounting rule is None — a write-off
   on one of those succeeds and posts **nothing** to the GL, silently.
4. **6e** flag the bank's maker-checker tasks (`MAKER_CHECKER_TASKS`), then
   **assert the middleware's own permission codes are NOT flagged**, then
   optionally flip the global switch (`ENABLE_MAKER_CHECKER`).

**Step 6e's assertion is not configurable and runs on every provision.** It is
derived from the script's own `READ_PERMS`/`WRITE_PERMS` arrays rather than a
copied list, so adding a permission to the middleware protects it automatically.
If it finds one flagged it unflags it and says so.

> [!IMPORTANT]
> **Apache Fineract SEEDS three of the middleware's own write permissions as
> maker-checkerable.** This is not a guard against a careless console user — a
> virgin cell arrives with part of the customer rail already flagged. Verified
> in `0002_initial_data.xml` on 2026-09-18 (66 of 834 seeded permissions carry
> `can_maker_checker="true"`):
>
> | code | id | seeded | what it is for us |
> |---|---|---|---|
> | `APPROVE_SAVINGSACCOUNT` | 359 | **true** | the approve leg of the wallet saga — every registration |
> | `ACTIVATE_CLIENT` | 313 | **true** | every registration |
> | `CREATE_ACCOUNTTRANSFER` | 379 | **true** | every customer transfer |
> | `CREATE_SAVINGSACCOUNT` | 189 | false | — |
> | `DEPOSIT_` / `WITHDRAWAL_SAVINGSACCOUNT` | 250/252 | false | — |
>
> The flag sits harmless while the global switch is off, so a cell can carry it
> for months and break only on the day someone enables maker-checker. Then
> registration and transfers park, and `FineractClient.failIfParkedByMakerChecker`
> correctly refuses them as `CoreUnknownOutcomeException` — the customer simply
> cannot register or transfer, by any route.
>
> So run 6e **before** enabling maker-checker on any new cell, and read its last
> line. "Keep the middleware's codes off the task list" is not enough advice on
> its own: Fineract puts three of them on that list for you.

On the ZW cell (2026-09-18) the assertion found `CREATE_SAVINGSACCOUNT` and
`CREATE_ACCOUNTTRANSFER` flagged. Only the second is seeded that way, and
nothing in the fork ever flips the first — so someone had used the console's
*Configure Maker Checker Tasks* screen. `PUT /permissions` merges rather than
replaces (`PermissionWritePlatformServiceJpaRepositoryImpl:58`, iterating only
the request's entries), but that screen posts every checkbox it renders, so one
save rewrites the whole maintenance set — which also explains why the two
seeded-true codes above were *not* flagged there. If the assertion keeps finding
codes flagged after a provision, that screen is where it is coming from.

READ permissions cannot be flagged at all: the same write service throws
`PermissionNotFoundException` for any code that is `READ_*`, ends `_CHECKER`, or
sits in the `special` grouping (`:62-64`). So the three `READ_PERMS` in the
assertion are structurally safe and only the write codes can ever trip it.

The parsing and set arithmetic behind 6b/6e live in `provision-lib.sh` and are
covered by `./provision-selftest.sh` (no cell, no network). Run it after
touching either — a false negative in the violation check is the difference
between a working customer rail and every deposit parking for an approver who
does not exist.

**The script runs under `set -euo pipefail`, so any helper that can legitimately
"find nothing" must be written to succeed anyway.** A `grep … | head -N | …`
returns nonzero both when grep matches nothing and when head closes early and
SIGPIPEs grep into 141; either kills the run *silently*, wherever it happens to
sit. That bit step 6b on the ZW cell: the "did you mean" hint for an unknown
permission code exited the script before it could print the warning it was
building, and 6c–6e — the maker-checker assertion among them — never ran. The
output simply stopped after the first role, with no error.

Read that as the general rule, not one fixed line: **a provisioning step may
fail loudly, never silently, and least of all while reporting someone else's
typo.** If you add a step, check what it does on the empty result. The selftest
covers the two shapes above by running them in a subshell with `-e` ON, so a
regression fails on your laptop rather than half-way through a cell.

### Enabling maker-checker for the BANK's own dual control

The back office WILL want maker-checker on (reversals, journals, write-offs,
product and user changes). Verified against the fork — all four pieces are
needed, and each alone looks like "maker-checker is broken":

1. **The global switch is seeded OFF** (`c_configuration` `maker-checker`,
   `enabled=false` from the first tenant changeset). Enable via the API —
   `PUT /v1/configurations/name/maker-checker` `{"enabled":true}` — which
   evicts the config cache immediately; a direct SQL UPDATE needs a Fineract
   restart to be seen. While it is off, every per-task flag is dead, which is
   why "Configure Maker Checker Tasks" alone changes nothing.
2. **Flag the tasks** (`PUT /v1/permissions` `{"permissions":{"CREATE_JOURNALENTRY":true,…}}`
   or the console's Configure Maker Checker Tasks) — immediate, uncached.
   Never flag the eight middleware codes above.
3. **The maker must NOT hold `ALL_FUNCTIONS` or `CHECKER_SUPER_USER`** — a
   superuser maker self-checks and posts directly (`AppUser.hasPermissionTo`
   short-circuits on `ALL_FUNCTIONS`), so testing maker-checker as the
   `mifos` admin proves nothing. Test with the real least-privilege roles.
4. **Checkers need the `<CODE>_CHECKER` permission** for each task (seeded
   for most codes) — it both scopes what their Checker Inbox
   (`GET /v1/makercheckers`, pending-only) shows and authorizes
   `POST /v1/makercheckers/{id}?command=approve|reject`. Self-approval is
   refused unless `enable-same-maker-checker` is enabled (seeded off — leave
   it off; it defeats the point).

Five actions have **no seeded `_CHECKER` row** (`UNDOWRITEOFF_LOAN`,
`RECOVERYPAYMENT_LOAN`, `REVERSE_JOURNALENTRY`, `ADJUSTTRANSACTION_SAVINGSACCOUNT`,
`UNDO_WAIVECHARGE`): under maker-checker only a `CHECKER_SUPER_USER` can
approve those until a fork changeset adds the rows — flag them as tasks only
with that understood.

### The logical business date (bites every cell restored from a dump)

Fineract dates writes by its **logical business date**, not the wall clock,
whenever the `enable-business-date` configuration is on. Nothing advances that
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

> [!IMPORTANT]
> **Global-configuration keys are kebab-case: `enable-business-date`, not
> `enable_business_date`.** Migration `0149_update_global_configuration_names`
> renamed every one of them:
> ```sql
> UPDATE c_configuration SET name = REPLACE(REPLACE(LOWER(name), '_', '-'), ' ', '-');
> ```
> A query using the old underscored name matches nothing — and "no row" reads
> exactly like "the feature is off", in SQL and over the API alike. That
> mis-diagnosis cost a full debugging round on the first cell. The Java
> constants are the source of truth (`GlobalConfigurationConstants`).

```sh
cd deploy/fineract
docker compose exec -T fineract-db psql -U fineract -d fineract_default \
  -c "SELECT id, name, enabled FROM c_configuration WHERE name = 'enable-business-date';"
docker compose exec -T fineract-db psql -U fineract -d fineract_default \
  -c "UPDATE c_configuration SET enabled = false WHERE name = 'enable-business-date';"
docker compose restart fineract
```

The restart is **required**, not tidiness: the flag is cached
(`@Cacheable("configByName")` in `GlobalConfigurationRepositoryWrapper`) and
evicted only on the API update path, so a direct SQL write stays invisible
until the process restarts. Safe once the tenant DB is migrated — do not do it
while a first boot is still running migrations.

Note this bypasses Fineract's command-audit trail (no `m_portfolio_command_source`
row). Acceptable on a cell you are standing up; on a live cell, prefer the API
if you can get it to answer.

After the restart, `POST /v1/businessdate` starts refusing with
"business date is not enabled" — that is expected, not a new fault.

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

`.env` in the repo root. The `FINERACT_*` values come from what
`provision-cell.sh` printed — including **`FINERACT_PAYMENT_TYPE_ID`**, which
is boot-required: Fineract validates `paymentTypeId` on every savings
transaction, so a cell without it rejects every deposit and withdrawal.

```sh
umask 077
cat > .env <<EOF
SPRING_PROFILES_ACTIVE=uat          # uat keeps Swagger on; prod disables it
INNBUCKS_COUNTRY=ZW
INNBUCKS_CORE_PROVIDER=fineract

POSTGRES_USER=innbucks
POSTGRES_PASSWORD=$(openssl rand -base64 36 | tr -d '\n')

JWT_SIGNING_KEY=$(openssl rand -base64 48 | tr -d '\n')
VERIFICATION_SIGNING_KEY=$(openssl rand -base64 48 | tr -d '\n')
NATIONAL_ID_HMAC_KEY=$(openssl rand -base64 48 | tr -d '\n')
OTP_HMAC_SECRET=$(openssl rand -base64 48 | tr -d '\n')
AUDIT_HMAC_SECRET=$(openssl rand -base64 48 | tr -d '\n')

FINERACT_BASE_URL=https://fineract:8443/fineract-provider/api
FINERACT_TENANT_ID=default
FINERACT_READ_USERNAME=innbucks-mw-read
FINERACT_READ_PASSWORD=<from provisioning>
FINERACT_WRITE_USERNAME=innbucks-mw-write
FINERACT_WRITE_PASSWORD=<from provisioning>
FINERACT_OFFICE_ID=1
FINERACT_SAVINGS_PRODUCT_ID=<from provisioning>
FINERACT_PAYMENT_TYPE_ID=<from provisioning>
FINERACT_CURRENCY=USD

NOTIFY_PROVIDER=innbucks-gateway
NOTIFY_API_URL=<platform team>
NOTIFY_API_KEY=<platform team>
NOTIFY_API_USERNAME=<platform team>
NOTIFY_API_PASSWORD=<platform team>

CORS_ALLOWED_ORIGINS=https://<the FE origin>
TRUSTSTORE_PASSWORD=<from the TLS step>
EOF
```

`NOTIFY_PROVIDER` is a hard switch, not auto-detection: filling in the four
`NOTIFY_API_*` values while leaving `console` selected keeps OTP codes in the
logs and sends nothing. Gateway mode makes all four boot-required.

### The compose override (truststore, loopback, forwarded headers)

```yaml
# docker-compose.override.yml — gitignored, per-box
services:
  middleware:
    # !override REPLACES the base list. Without the tag Compose MERGES them,
    # and the container tries to bind 0.0.0.0:8090 and 127.0.0.1:8090 at once:
    #   Error starting userland proxy: listen tcp4 0.0.0.0:8090: address already in use
    ports: !override
      - "127.0.0.1:8090:8090"
      - "127.0.0.1:9090:9090"
    volumes:
      - ./deploy/fineract/ssl/innbucks-cell-truststore.p12:/ssl/truststore.p12:ro
    environment:
      # Makes X-Forwarded-Prefix take effect, so springdoc emits URLs under
      # the edge's path prefix instead of the container root.
      SERVER_FORWARD_HEADERS_STRATEGY: framework
      # Without this every customer shares ONE rate-limit bucket keyed on the
      # proxy's address — the /register limiter either blocks everyone or
      # means nothing. Only safe because 8090 is loopback-only above.
      RATE_LIMIT_TRUST_FORWARDED_FOR: "true"
      JAVA_TOOL_OPTIONS: >-
        -XX:MaxRAMPercentage=70.0
        -Djavax.net.ssl.trustStore=/ssl/truststore.p12
        -Djavax.net.ssl.trustStorePassword=${TRUSTSTORE_PASSWORD:?set TRUSTSTORE_PASSWORD in .env}
```

Then start it. **Pull a published, scanned image — do not build on the box.**
The Release workflow (`.github/workflows/release.yml`) gates on the test
suite, builds, Trivy-scans CRITICAL/HIGH, pushes to GHCR and attests SLSA
provenance. Every merge to `main` publishes `:latest` and
`:sha-<commit>`; a `v*` tag publishes that too.

```sh
# Log in once per box (classic PAT, read:packages scope is enough to pull).
echo "$GHCR_TOKEN" | docker login ghcr.io -u mpofuslim --password-stdin

# Pin the version this cell runs. :latest is mutable — fine for staging,
# never for a cell you need to reason about after the fact.
#
# The tag is the FULL 40-character commit SHA (the workflow uses
# `type=sha,format=long`). A 7-character short SHA is not a tag that exists
# and fails with "manifest unknown" — copy it from `git rev-parse origin/main`,
# not from the abbreviated log.
sed -i '/^IMAGE_TAG=/d' .env && echo "IMAGE_TAG=sha-$(git rev-parse origin/main)" >> .env

# Plain `docker pull`, not `docker compose pull`: the middleware service still
# carries a `build:` section (for the local-build fallback below), and compose
# treats buildable services inconsistently across versions.
docker pull "ghcr.io/mpofuslim/innbucks-middleware:sha-$(git rev-parse origin/main)"

# --force-recreate is not optional: the compose override bind-mounts the
# truststore as a SINGLE FILE, which binds the inode. A plain restart keeps
# the old container (and the old image) alive.
docker compose up -d --no-build --force-recreate middleware
docker compose logs -f middleware
```

Verify what you're about to run was built by this repo, not handed to you:

```sh
gh attestation verify oci://ghcr.io/mpofuslim/innbucks-middleware@<digest> \
  --repo MpofuSlim/InnBucksMiddleware2.0
```

Rolling back is then re-pinning `IMAGE_TAG` to the previous `sha-` tag and
`docker compose up -d` — no rebuild, and the exact bytes that were running
before.

Building locally is the fallback when you need an unmerged change on the box.
If the box's buildx predates 0.17 (`compose build requires buildx 0.17.0 or
later`), use the legacy builder — the Dockerfile has no BuildKit-only
features, so the image is identical:

```sh
DOCKER_BUILDKIT=0 docker build -t ghcr.io/mpofuslim/innbucks-middleware:dev .
docker compose up -d
```

A locally built image is unscanned and unattested. Treat it as a debugging
tool, not a deploy.

Three lines confirm a good boot: `Started MiddlewareApplication`,
`ProductionSecretsGuard: all 5 guarded secrets pass`, and
`SmsSender: InnBucks notification gateway at <url>` (not the console stub).

### The edge

The middleware must not be published directly — plain 8090 puts customer PINs
and JWTs in clear, and a directly reachable origin lets anyone forge
`X-Forwarded-For` past the rate limiter. Terminate TLS at nginx and proxy to
loopback. Serving under a path prefix (`/middleware`) needs the prefix header,
or Swagger loads but every "Try it out" 404s:

```nginx
location = /middleware { return 301 /middleware/; }

# Internal-only surface — the Fineract core-event webhook lives under
# /internal/** and must be reachable ONLY over the private cell network
# (Fineract calls http://innbucks-middleware:8090 directly). This deny is
# layer 3 of the three-files-must-agree pattern: the controller's shared
# token and SecurityConfig's permitAll are the other two. nginx picks the
# LONGEST matching prefix, so this wins over /middleware/ below regardless
# of order.
location /middleware/internal/ { return 404; }

location /middleware/ {
    proxy_pass http://127.0.0.1:8090/;   # trailing slash strips the prefix
    proxy_http_version 1.1;
    proxy_set_header Host               $host;
    proxy_set_header X-Forwarded-For    $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto  https;
    proxy_set_header X-Forwarded-Prefix /middleware;
    proxy_read_timeout 60s;
}
```

Placement inside the `server` block does not matter — nginx picks the longest
matching prefix. Behind Cloudflare, restrict 443 at the firewall to
[Cloudflare's ranges](https://www.cloudflare.com/ips/); otherwise the origin
is reachable directly and the forwarded IP is attacker-controlled. Never use
Cloudflare's "Flexible" mode — it leaves the edge-to-origin hop in plaintext.

**Do not proxy Fineract itself to the public internet.** 8443 is loopback-only
by design; an nginx `location` that forwards to it undoes the whole
containment story and puts the core banking API online. Admin access is the
SSH tunnel.

Verify the prefix landed before handing the URL over — this is the check that
catches a half-working edge:

```sh
curl -s https://<host>/middleware/v3/api-docs | jq '.servers'
# must show https://<host>/middleware — a bare "/" means the prefix header
# is not reaching the app and every FE call will 404
```

### End-to-end smoke, through the middleware

1. `POST /register` (Idempotency-Key header) → 201 with the wallet id.
2. `/auth/otp/request` → real SMS on the gateway provider. Codes only appear
   in the logs on the `console` stub, which a deployment profile flags with a
   page-worthy ERROR at boot.
3. `/auth/otp/verify` → `POST /auth/pin/set` → `POST /auth/login` → Bearer token.
4. `GET /me/accounts` → the wallet with balance 0.
5. `POST /transactions/deposit` then `/transfer` — watch
   `ledger_transaction` land COMPLETED and, for anything parked,
   `innbucks.ledger.parked_overdue` stay at zero.

Tell the FE team three things or they will lose a day each: **amounts cross
the API in MINOR units** (cents), **every state-changing call needs an
`Idempotency-Key`** (and reusing one replays the original response, including
a failure), and a fresh customer sits in `pending_verification` until the PIN
is set — login before that is supposed to fail.

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
