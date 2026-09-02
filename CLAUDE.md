# CLAUDE.md

Project context for Claude / Claude Code. Read this first on any new session.

## What this is

**InnBucks Middleware 2.0** — Spring Boot middleware between the **InnBucks
super-app** (mobile) and the deployment's **core banking system**, behind a
single seam: the `CoreBankingPort`. First core: **Apache Fineract** (own fork,
`MpofuSlim/fineract`). Later: **Veengu**. The retired predecessor
(`MpofuSlim/OradianMiddleware`) is read-only reference — its proven
core-agnostic code (auth slices, idempotency, audit, country-awareness) was
ported here; its Oradian integration was NOT and will not be.

* Stack: Spring Boot 4.0.6, Java 21 LTS, Spring Data JDBC, Postgres 16,
  Flyway, Micrometer + OpenTelemetry, Springdoc OpenAPI.
* App port `8090`, management/actuator port `9090`.
* Deployed **per (country, core) cell**: `INNBUCKS_COUNTRY` +
  `INNBUCKS_CORE_PROVIDER` pin each deployment. Never runtime multi-tenant,
  never a runtime core switch.

> [!IMPORTANT]
> **Branch naming: always `feature/<short-kebab-description>`, cut from `main`.**
> Never commit or push feature work on a session's auto-assigned
> `claude/<random-words>` branch. Create the `feature/*` branch first; open a
> **draft** PR.

## Module map

```
middleware-corebanking-api   The CoreBankingPort + value types + core-neutral
                             exceptions. ZERO framework deps, pure Java.
middleware-core              Everything core-agnostic: auth (login/PIN/OTP/
                             refresh/JWT), customer, audit chain, idempotency,
                             rate limiting, country/MSISDN SPI, config.
middleware-adapter-fineract  Fineract implementation of the port (skeleton —
                             client lands in the next slice).
middleware-app               Boot assembly: application class, yamls, Flyway
                             migrations, Dockerfile target. The one deployable.
```

Build: `./mvnw verify` at the root. Run locally:
`SPRING_PROFILES_ACTIVE=dev ./mvnw -pl middleware-app spring-boot:run`
(or `set -a; source .env; set +a` first — see `.env.example`).

## CoreBankingPort — the invariants (do not weaken)

* **Money crosses the port in minor units** (`MinorUnits`): one major↔minor
  conversion point per adapter, and every amount echoed by the core is
  cross-checked against the amount sent (the 100x-charge guard).
* **Writes are NEVER auto-retried.** Reads may retry on transient failure.
* **Ambiguous write outcomes throw `CoreUnknownOutcomeException`** — the row
  is parked and reconciled via `getTransaction(TxRef)`; never guessed, never
  blind-retried, never auto-expired. Blocked beats double-charged.
* Orchestration branches on `capabilities()` flags (`SERVER_SIDE_DEDUP`,
  `CLIENT_ASSIGNED_EXTERNAL_ID`, `REVERSAL`) — never on `instanceof` adapter.
* Adapters map upstream failures to the core-neutral taxonomy
  (`CoreAuthException` / `CoreClientException` / `CoreServerException` /
  `CoreTransientException` / `CoreUnknownOutcomeException`); nothing upstream
  of an adapter sees a core-specific exception or raw body.

## Auth model

* **Inbound (mobile → middleware)**: customer-facing JWT, HS256 by default
  (`JWT_SIGNING_KEY`); RS256-with-`kid` minting + dual-verify decoder are
  SHIPPED behind `JWT_SIGNING_ALG`/`JWT_PRIVATE_KEY`/`JWT_PUBLIC_KEY`
  (slice 5 — flip in the staged order in `.env.example`). Issued by
  `POST /auth/login` (MSISDN + Argon2id PIN → 10-min access + 30-day
  rotating opaque refresh, family revocation on replay AND on device
  mismatch — the refresh token only rotates on the device it was issued to).
  `sub` = customer UUID; claims: `country`, `kyc_tier`, `scopes`, `did`,
  `nid_hash`, `auth_time`. High-value withdrawals/transfers additionally
  need a step-up SMS approval bound to the exact transaction
  (`X-Step-Up-Token`; `innbucks.stepup.*`).
* **SMS/notifications ride the InnBucks notification gateway**
  (`NOTIFY_PROVIDER=innbucks-gateway` + `NOTIFY_API_*` — same platform
  gateway as the ticketing fleet; email surface pinned for later), with an
  optional WhatsApp fallback channel (`WHATSAPP_FALLBACK_ENABLED` +
  `WHATSAPP_GATEWAY_URL`/`WHATSAPP_API_KEY`). The `console` provider is a
  dev/UAT stub only — deployment profiles scream at boot on it.
* **Verification/step-up tokens are signed with a SEPARATE key**
  (`VERIFICATION_SIGNING_KEY`). Equal keys = access-token minter can mint
  PIN-reset tokens (account takeover); `ProductionSecretsGuard` refuses to
  boot on equality, in every environment.
* **Outbound (middleware → Fineract)**: HTTP Basic with TWO least-privilege
  AppUsers (`innbucks-mw-read` / `innbucks-mw-write`), per-command Fineract
  permissions, never `ALL_FUNCTIONS`. `Fineract-Platform-TenantId` pinned per
  cell. The customer JWT NEVER reaches Fineract. Fineract's self-service API
  is DEAD upstream (FINERACT-2480 removed it as insecure) — do not resurrect.
* Customer ↔ core mapping: `customer.core_provider` + `customer.core_external_id`;
  for Fineract the client `externalId` = our customer UUID (client-assigned,
  crash-recoverable by GET).

## Security invariants (A02/A09 — a change that weakens any needs a called-out reason)

* **Fail-closed secrets guard** (`ProductionSecretsGuard`): "deployment" = an
  active-profile set with NO `dev`/`test`/`it`/`local` profile, **including
  the empty set**. Boot-required, ≥32 chars, no placeholder markers:
  `JWT_SIGNING_KEY`, `VERIFICATION_SIGNING_KEY`, `NATIONAL_ID_HMAC_KEY`,
  `OTP_HMAC_SECRET`, `AUDIT_HMAC_SECRET` (+ non-blank `DATASOURCE_PASSWORD`).
  Generate each: `openssl rand -base64 48`. All distinct values.
* **Low-entropy secrets are HMAC-keyed, never bare-hashed**: OTP codes
  (`OtpHasher`), national IDs (`NationalIdHasher`). PINs: Argon2id
  (`PinHasher`). Refresh tokens: SHA-256 of a high-entropy opaque secret.
* **The two Fineract AppUsers are stored at bcrypt cost 6, `mifos` at 10** —
  the same entropy argument, run the other way. Fineract's
  `DaoAuthenticationProvider` has NO verification cache, so `matches()` runs on
  EVERY request and every money movement pays the hash; cost 10 measured 187ms
  vs cost 6 at 98ms (staging, 2026-08-27) — ~85ms of pure CPU per call. It is
  safe ONLY because `provision-cell.sh --gen-password` mints ~122 bits (20
  chars × 68-char alphabet): at that size the search space is the defence and
  the KDF cost is irrelevant. **Check the plaintext is still generated before
  ever writing a cost-6 hash** — a memorable password there makes cost 6
  indefensible. `mifos` keeps 10 (interactive, low-volume). **Not durable:**
  the cost lives in the hash, not in config, so ANY password change through
  Fineract's API silently re-hashes at 10 and reverts it, with no error —
  re-apply and verify `{bcrypt}$2b$06$` after every rotation. Full reasoning,
  measurement and procedure in `docs/fineract-cell-runbook.md`.
* **Tamper-evident audit chain**: every `audit_event` row carries `row_hmac`
  (content seal) + `chain_hmac = HMAC(key, prev ‖ row_hmac)` (deletion/reorder
  evidence), serialised via `audit_chain_head` `SELECT … FOR UPDATE` in a
  REQUIRES_NEW tx. Nightly `AuditIntegrityVerifier`: content tamper →
  `innbucks.audit.integrity.broken` (page), chain break →
  `innbucks.audit.chain.broken` (ticket). This chain carries the real customer
  identity per money movement — it is the compensating control for Fineract
  attributing everything to the service account. Launch gate, not polish.
* **Idempotency claim-row**: `IdempotencyService` claims the key with
  `INSERT … ON CONFLICT DO NOTHING` (status 0 sentinel) BEFORE running work —
  concurrent same-key requests can never double-execute. Replays return the
  ORIGINAL stored status. Same key + different body → 422; fresh claim in
  flight → 409; stale claim (>60s) taken over.
* **Credential-spray detection** (`anomaly/AuthAnomalyDetector`): the
  brute-force shape the other two controls are blind to — one source trying a
  FEW PINs against MANY accounts. Per-account lockout fires at 7 failures (so
  an attacker stops at 5 and moves on) and the per-IP bucket allows 15
  logins/min (21,600 "legal" attempts a day), so a spray across 10,000 MSISDNs
  currently triggers nothing. Thresholds count **DISTINCT ACCOUNTS per source
  per window, never attempts** — that asymmetry is what makes auto-blocking
  safe behind NAT (an office legitimately produces many failures from one
  address, but not against 30 different accounts in 15 minutes). Alert at 10,
  block the source from the auth endpoints at 30 (`innbucks.security.anomaly.*`
  / `AUTH_ANOMALY_*`; `block-enabled=false` runs observe-only while tuning).
  Fires ONCE per source per window — auditing every failure would serialise an
  attack on `audit_chain_head`'s row lock and take money movements down with
  it. **Nothing in the detector may throw**: it runs on the sign-in path, and a
  monitoring fault must never become an auth outage — it fails open, loudly.
  Depends on correct client-IP resolution, so it is only as good as
  `RATE_LIMIT_TRUSTED_PROXY_COUNT` (0 = every user collapses into one
  "source"). Metrics `innbucks.auth.failures` (the rate signal — Prometheus
  catches the botnet case the per-source detector cannot) and
  `innbucks.auth.spray.detected`; rules in
  `deploy/prometheus/auth-anomaly-alerts.yml`. Pinned by
  `AuthAnomalyDetectorTest` + the block cases in `AuthRateLimitFilterTest`.
* **The failed-PIN counter is ONE atomic statement, and `LoginService.login`
  owns NO transaction.** `CustomerLockoutStore` increments
  `failed_pin_attempts` and applies the lockout transition in a single
  `UPDATE … RETURNING` (guard `failed_pin_attempts + 1 >= ?` — Postgres
  evaluates SET-list expressions against the OLD row, so the `+ 1` is
  load-bearing and the short spelling silently moves the lock from the 7th
  wrong PIN to the 8th). It replaced a read-modify-write under which K racing
  wrong PINs advanced the counter by 1, so a concurrent attacker never reached
  the cap at all. **Never re-add `@Transactional` to `login()`** (nor to the
  store): a transaction acquires its pooled connection EAGERLY at begin and
  would hold it across the ~100-300ms Argon2id compare — including the
  dummy-hash burn on the unknown-MSISDN branch, the highest-volume path under
  a spray — draining the pool with threads doing arithmetic. The increment now
  survives the thrown `InvalidCredentialsException` because it commits on an
  autocommit connection before the throw, NOT because of `noRollbackFor`.
  Pinned by `CustomerLockoutStoreIntegrationTest`,
  `LoginLockoutConcurrencyIntegrationTest` and
  `LoginServiceTransactionBoundaryTest`.
* **CI supply chain**: every third-party GitHub Action is pinned to an
  immutable commit SHA with a `# vX.Y.Z` comment. Least-privilege
  `permissions:` per workflow.
* **Release pipeline** (`.github/workflows/release.yml`): a merge to `main` or
  a `v*` tag runs the FULL test suite as a gate, builds the image, Trivy-scans
  CRITICAL/HIGH against the governed `.trivyignore` BEFORE any push, then
  pushes `ghcr.io/mpofuslim/innbucks-middleware:{latest,sha-<commit>}` with a
  SLSA provenance attestation + SBOM. The test gate exists because the
  Dockerfile builds with `-DskipTests` — without it a merge could publish a
  fully-attested image that was never tested. Deploys PULL a pinned
  `sha-<commit>`; building on the box yields an unscanned, unattested image
  and is a debugging tool, not a deploy.

## Caching policy — names only, never money

`CustomerNameResolver` (middleware-core) is the ONE cache in front of the core,
and it caches ONE thing: a customer's first/last name, keyed by
`core_external_id`, `expireAfterWrite` (`innbucks.core.profile-cache.*`,
default 5m). Three paths needed a name and each paid a full `getProfile`:
`GET /me/profile` (polled), `/accounts/lookup`, and the credit leg of every
transfer alert.

**Do NOT turn this into a caching decorator around `CoreBankingPort`.** The
safety argument is entirely about scope: nothing in this service DECIDES on a
name. Ownership comes from the core's live account list, money from the live
balance read, account status from the local row — a cached balance or a cached
account list would be a correctness bug no TTL makes safe. In
`RecipientLookupService` the ordering is load-bearing: the live ownership check
runs BEFORE the cached name, so a cached name can never resurrect a recipient
the core no longer lists.

Other invariants: **successes only** (a thrown exception or a null profile is
never cached, so a core blip cannot pin a miss for a whole TTL); the TTL is a
**staleness budget** — the worst-case delay before a teller's correction in the
core reaches the app; and because this middleware has no name-write path, TTL is
the complete invalidation story. **If a name-write path is ever added here it
MUST call `invalidate(coreExternalId)`.** Watch
`innbucks.core.profile_cache{outcome=hit|miss}`. Pinned by
`CustomerNameResolverTest`.

`TransactionNotifier.CoreReads` is NOT a second cache and is not covered by the
rule above: it is a memo scoped to ONE `deliver()` call — sub-second, discarded
after, never shared between deliveries or threads, no TTL. Both legs of a
transfer describe the same instant, so fetching an account once is strictly
more consistent than fetching it twice milliseconds apart. It memoizes
**successes only**, so a failed read never denies the other leg its own
best-effort attempt.

## Country-aware architecture (mandatory)

* `INNBUCKS_COUNTRY` env var pins each deployment (startup fails without it).
* SPIs needing a per-country impl: `MsisdnNormalizer` (currently
  `KenyaMsisdnNormalizer`; registry fails startup if the active country has
  no normalizer).
* Every JWT carries `country`; MDC carries it; log pattern is
  `[country,correlationId,traceId,spanId]`.

## Persistence + Spring Data JDBC gotcha (don't repeat)

* `CrudRepository.save()` on an entity with a manually-assigned `@Id` emits
  **UPDATE** (0 rows) instead of INSERT — the row never lands. Use
  **`JdbcTemplate.update()` directly** for every INSERT with a client-generated
  `@Id` (`RefreshTokenService.persist`, `DevDataSeeder`, test seeds).
  Repository `save()` is fine for UPDATEs of loaded entities.

## Spring Boot 4 modular auto-config gotcha (don't repeat)

* **Flyway** is not auto-configured by our dependency set — `config/FlywayConfig`
  runs it explicitly via `@PostConstruct flyway.migrate()`. Don't remove it.
* No auto `ObjectMapper` bean either — `IdempotencyConfig` provides it.
* `EndpointRequest.toAnyEndpoint()` moved package — `SecurityConfig` uses a
  plain `/actuator/**` matcher.
* `@AutoConfigureMockMvc` moved — tests build `MockMvc` manually via
  `MockMvcBuilders.webAppContextSetup(...).apply(springSecurity()).build()`.
* `@MockitoBean` is unreliable — controller tests use `@TestConfiguration`
  with `@Primary` `Mockito.mock(...)` beans.
* New Boot-managed deps sometimes need explicit versions (`bcprov-jdk18on`);
  Testcontainers uses the imported BOM.

## Fineract wire shapes — READ THE FORK, do not infer

> [!IMPORTANT]
> **Before modelling any Fineract response, open the endpoint in
> `MpofuSlim/fineract` and read what it actually serialises.** This is not
> optional diligence — inferring the statement envelope from the Fineract docs
> instead of the source shipped a `500` on every customer statement, and cost
> three follow-up PRs to walk back.

Fineract serialises API responses with **Gson**
(`DefaultToApiJsonSerializer` → `GoogleGsonSerializerHelper`), and Gson
reflects over **FIELDS, not getters**. That one fact explains most surprises:

- `GET .../transactions/search` returns `org.springframework.data.domain.Page`
  → the JSON keys are `PageImpl`'s fields, so **`total` / `content` /
  `pageable`** — NOT `totalElements` (a getter, so Gson never emits it) and
  NOT Fineract's legacy `totalFilteredRecords` / `pageItems` wrapper, which
  other endpoints genuinely do use.
- `LocalDate` → `LocalDateAdapter` → a **`[yyyy, m, d]` ARRAY**, not an ISO
  string.
- `ExternalId` → `ExternalIdAdapter` → a **bare string**, or `JsonNull` when
  empty; Gson drops nulls, so the key is usually just absent.
- Plain enums (e.g. `TransactionEntryType`) have no adapter → `"CREDIT"` /
  `"DEBIT"`.
- `transient` fields are excluded by Gson, so they never appear.

Where to look, per endpoint: the `*ApiResource` class for the return type,
then that DTO's FIELDS, then `GoogleGsonSerializerHelper.registerTypeAdapters`
for any type with a custom rendering.

**A contract test that stubs a shape nobody observed pins your assumption, not
the upstream service** — it stays green while production 500s. Every stub
either transcribes a real response or matches a serializer read out of the
fork; say which, in the test.

**`docs/savings-account-approval-api.md`** applies all of the above to the
savings lifecycle (`?command=approve|activate|reject|withdrawnByApplicant|
undoapproval`) for the **CBS back-office console**, which talks to Fineract
DIRECTLY and must keep doing so — this middleware has no admin surface, and
routing an operator action through it would put a customer-scoped JWT in front
of a back-office decision. Do not add approval endpoints here to serve that
screen. The doc is field-verified: per-command parameter whitelists are STRICT
(`checkForUnsupportedParameters` 400s on any extra key, and `activate` uniquely
rejects `note` while `undoapproval` rejects `locale`/`dateFormat`), dates are
written as strings but read back as `[y, m, d]` arrays, "future" is measured
against Fineract's BUSINESS date, and a maker-checker-parked command returns a
success-shaped 200 with `rollbackTransaction: true` and no `resourceId`.

**`docs/client-legal-form-api.md`** does the same for `POST /v1/clients` and the
Person/Entity split, for the same console and under the same
don't-route-it-through-here rule (the middleware only ever creates PERSON
clients, for individual mobile customers). The finding worth knowing even if you
never touch that screen: Fineract's name validation is **NOT keyed on
`legalFormId`**, so `firstname`+`lastname` with `legalFormId: 2` is **accepted**
— 200, no error, a company silently written into the person-shaped columns with
no `m_client_non_person` row. Entity needs `fullname` INSTEAD of the name parts
(the two are mutually exclusive) plus the optional `clientNonPersonDetails`
block, and `legalFormId` is asymmetric: sent as an integer, read back as a
`legalForm` OBJECT. Two more, measured on the cell 2026-09-02 (an earlier
revision of the doc said `clientNonPersonDetails` was all-optional — it is not):
the block is optional but **`constitutionId` is required whenever it is sent**
(the `ClientNonPerson` constructor always validates), so a cell with no
`Constitution` code values cannot record company details at all; and **on
UPDATE the block is silently discarded unless the body also carries
`legalFormId: 2`** — a clean 200 whose `changes` names only the other fields —
because the create-when-absent branch reads the legal form from the request,
not from the stored row. Same silent-write family as the finding above, one
verb along.

**`docs/running-reports-api.md`** covers `GET /v1/runreports/{name}` for the same
console, and carries a finding that applies FAR beyond reports: **a 403 from
Fineract does NOT mean "not authorised."** `PlatformDataIntegrityExceptionMapper:52`
and `PlatformDomainRuleExceptionMapper:53` both map to `Status.FORBIDDEN`, so a
SQL failure and a business-rule veto are indistinguishable from a real permission
denial by status alone — read `errors[].userMessageGlobalisationCode` from the
body instead. (This is why `FineractErrorMapper` already splits Fineract's 403
into `CoreClientException` for domain vetoes vs auth; the same care is owed
anywhere else a 403 is interpreted.) Report parameters ride a `R_<variable>`
prefix that `AbstractReportingProcessService:36-47` strips — anything unprefixed
is silently ignored — and `ReportParameterData` does NOT expose
`parameter_variable`, so the name→variable map has to come from
`stretchy_parameter`.

## Tests

* Unit tests live beside their classes in `middleware-core` /
  `middleware-corebanking-api` (no Spring context, no Docker).
* `@SpringBootTest` integration tests live in `middleware-app`, use the shared
  Postgres **Testcontainer** via `support/PostgresTestContainer` `@Import` +
  `@ServiceConnection`, `TRUNCATE` their tables in `@BeforeEach`, and seed via
  `JdbcTemplate` (not `repository.save`). They need Docker — CI runs them.
* Every external-HTTP client added here MUST get a standalone-WireMock
  contract test (no `@SpringBootTest`): one test per observed response shape,
  outbound wire-contract verification (`wireMock.verify(...)`), a
  connect-refused case, and guard-rail `verify(0, ...)` cases. This is the
  definition of done for the Fineract adapter.

## Operational conventions

* **No real secrets in committed files.** `application-dev.yaml` carries only
  marked `dev-only` placeholders (the marker is load-bearing — the guard
  rejects it under deployment profiles). `.env.example` committed, `.env`
  gitignored.
* Swagger UI at `http://localhost:8090/swagger-ui.html` (dev/uat), disabled in
  prod. Every controller: `@Tag` + `@Operation` + `@ApiResponses`; every DTO
  record: `@Schema` descriptions + examples. Public endpoints carry
  `@SecurityRequirements({})`.
* Containers pin `-Duser.timezone=UTC` in the Dockerfile ENTRYPOINT.
* Single-replica per cell assumption (in-memory rate limits, unlocked
  `@Scheduled` jobs) — wrap with ShedLock before ever scaling horizontally.

## Environments (EC2, af-south-1)

Three boxes, deployed to over SSH by the operator. **This repo is PUBLIC —
never commit hostnames/IPs, key filenames, or anything else that targets
these machines.** Connection details live in the operator's local
`~/.ssh/config` under these Host aliases; ask the operator to run commands
there, or reference the aliases when writing runbook snippets:

* `innbucks-ticketing-staging` — staging box running the ticketing fleet.
* `innbucks-cbs-staging` — staging box for the CORE BANKING cell: the
  Fineract stack (`deploy/fineract`) + this middleware (root compose) per
  `docs/fineract-cell-runbook.md`.
* `innbucks-prod` — production. Deliberate, runbook-driven changes only.

### Deploying to a cell after a merge

> [!IMPORTANT]
> **Every time a PR merges to `main`, output the exact pull + run commands for
> the new image.** This is a standing expectation — don't make the operator ask.

Deploys are manual `docker compose` on the box. The Release workflow gates on
the test suite, Trivy-scans, pushes `ghcr.io/mpofuslim/innbucks-middleware`
as `:latest` **and** `:sha-<commit>`, and attests provenance — so the box
never builds, it pulls. Confirm the merge commit's Release run is green first.

```sh
cd ~/InnBucksMiddleware2.0
git pull

# The tag is the FULL 40-char SHA (metadata-action `type=sha,format=long`).
# A short SHA is not a tag that exists — it fails with "manifest unknown".
TAG="sha-$(git rev-parse origin/main)"

sed -i '/^IMAGE_TAG=/d' .env && echo "IMAGE_TAG=$TAG" >> .env
docker pull "ghcr.io/mpofuslim/innbucks-middleware:$TAG"

# --force-recreate is NOT optional: the compose override bind-mounts the
# truststore as a single FILE, which binds the inode; a plain restart keeps
# the old container and the old image. --no-build because the service still
# carries a build: section for the local-build fallback.
docker compose up -d --no-build --force-recreate middleware
docker compose logs -f middleware
```

Pin `IMAGE_TAG` to a `sha-` tag, never leave a cell on mutable `:latest` —
rollback is then re-pinning the previous tag and re-running the same two
commands, with the exact bytes that were running before. Full procedure
(TLS, truststore, edge, smoke) in `docs/fineract-cell-runbook.md`.

## Slice progression

1. **Scaffold + core port** — multi-module build; ported OradianMiddleware's
   core-agnostic packages (auth slices 1–11 minus the Oradian-coupled
   endpoints); clean Flyway V1; `CoreBankingPort` contract; security fixes
   baked in: keyed OTP HMAC, separate verification signing key, fail-closed
   secrets guard, audit row+chain HMAC + nightly verifier, idempotency
   claim-row + stored replay status.
2. **Local transaction ledger (V2)** — `ledger` package in middleware-core,
   ported from ticketing payment-service's proven design. Write-ahead:
   `LedgeredMovementExecutor` is THE ONLY way to run a money movement (opens
   the PENDING `ledger_transaction` row in a REQUIRES_NEW tx BEFORE the core
   call, then records what actually happened). `LedgerService.transition()`
   is the single lifecycle chokepoint: legal-transitions map (PENDING →
   SUBMITTED/COMPLETED/FAILED/UNKNOWN; SUBMITTED → terminal/UNKNOWN;
   UNKNOWN → terminal/SUBMITTED; terminals immutable — illegal requests are
   refused + counted `innbucks.ledger.illegal_transitions`, never applied,
   never thrown), same-tx `ledger_transaction_event` journal, tamper-evident
   audit rows (TXN_COMPLETED/TXN_FAILED/TXN_UNKNOWN) with the real customer
   identity. Exception mapping: the four provably-not-applied core exception
   types → FAILED + rethrow; `CoreUnknownOutcomeException` → UNKNOWN,
   returned (caller renders PROCESSING); ANY unclassified exception → UNKNOWN
   + rethrow (conservative — could have fired post-send).
   `LedgerReconciliationJob` (fixed-delay, `innbucks.ledger.*` config):
   stale PENDING is PARKED as UNKNOWN (the call may have been sent —
   deliberately different from ticketing's stale→FAILED, which is only safe
   because an undelivered code can't be paid); due UNKNOWN/SUBMITTED rows
   poll `getTransaction` with per-row isolation + exponential backoff
   (`reconcile_attempts`/`next_reconcile_at`); ONLY positive core outcomes
   resolve a row; UNKNOWN rows are never auto-expired; rows parked past
   `parked-alert-threshold` trip `innbucks.ledger.parked_overdue` (operator
   page). The port is resolved via `ObjectProvider` — sweeps degrade
   gracefully until the first adapter bean lands.

3. **Fineract adapter (slice 3a)** — `middleware-adapter-fineract` is real:
   `FineractClient` (two RestClients — reads ride the read-only AppUser,
   commands the write one; `Fineract-Platform-TenantId`; `Idempotency-Key`
   on every mutation; `locale`+`dateFormat` in every date-bearing body;
   correlation propagated as `X-Correlation-ID`), `FineractErrorMapper`
   (the taxonomy: write 5xx / HTTP 425 / read-timeout-mid-write →
   `CoreUnknownOutcomeException` because the command reached the core; only
   connect-phase failures → `CoreTransientException`; Fineract's 403
   domain-rule vetoes → `CoreClientException`, permission-flavoured 403 →
   auth), `FineractResilience` (breaker both ways, retry READS ONLY),
   `FineractAdapter` (capabilities SERVER_SIDE_DEDUP +
   CLIENT_ASSIGNED_EXTERNAL_ID; savings saga create→approve→activate with
   per-leg keys `<key>:create|approve|activate`, every leg resumable by
   POSITIVE re-read of actual state — never error-string sniffing;
   cell-currency check before every write; amount-echo cross-check after —
   mismatch parks, never succeeds). Port refinements this forced:
   `getTransaction(TransactionLookup)` (reconciliation needs kind + account
   context — Fineract indexes transactions under the savings account) and
   `openDepositAccount(...)` joined the port. **Reconciliation semantics**:
   deposits/withdrawals ALWAYS attach our ref as the savings-transaction
   externalId → found=COMPLETED, reversed=FAILED, 404-by-ref=POSITIVE
   never-landed FAILED. Transfers cannot carry the ref in
   `POST /v1/accounttransfers` (queryable by `?externalId=` but not
   settable) → absent transfer stays UNKNOWN, parked for the operator; the
   customer-retry path is still fully dedup'd upstream by Idempotency-Key.
   Adapter activates on `innbucks.core.provider=fineract` (then every
   `fineract.*` property is boot-required). **`FINERACT_PAYMENT_TYPE_ID`
   is boot-required too**: Fineract validates `paymentTypeId` as `notNull()`
   on every savings deposit/withdrawal, so a cell without one 400s every
   money movement — `provision-cell.sh` creates the payment type and prints
   the id. Contract pinned by standalone
   WireMock tests (29 cases). Gotchas: colon-bearing externalIds
   (`<uuid>:wallet`) hit the wire percent-encoded (`%3A`) — WireMock stub
   URLs must match the encoded form; and the `Idempotency-Key` we send is
   NOT our key — Fineract stores it in a `VARCHAR(50)` column, so
   `FineractIdempotencyKey.forCore` re-hashes our 64-char key (plus its
   saga-leg suffix) to 22 base64url chars. Re-hash, never truncate: the leg
   suffix is at the END, so truncation would collapse create/approve/activate
   into one key and Fineract would dedup the legs against each other.

3b. **Customer-facing endpoints through the port** — the mobile surface is
   live: `POST /register` (public, `@SecurityRequirements({})`, per-IP
   rate-limited via the new `ip-register` bucket, Idempotency-Key required —
   namespaced per MSISDN via `IdempotencyKeys.namespaced(scope, key)`
   (SHA-256, unit-separator between parts); createCustomer + wallet saga with
   per-step derived keys; customer row inserted first, core mapping updated
   after; a crashed registration leaves core_external_id NULL and the next
   attempt RESUMES on that row; fully-registered MSISDN → 409), `GET
   /me/profile` + `GET /me/accounts` (local identity merged with core
   names/balances; missing core mapping → 404 customer_not_registered),
   `POST /transactions/{deposit|withdraw|transfer}` (JWT `customer:write`;
   ownership checked against the CORE's account list BEFORE any claim/write —
   source for withdraw/transfer, target for deposit; key namespaced per
   customer → becomes idempotency_record key, ledger external_ref AND the
   upstream Idempotency-Key; all movement through `LedgeredMovementExecutor`;
   crash-retry with the same key answers from the EXISTING ledger row (never
   a second row — the core would replay the same result anyway; fresh attempt
   = fresh key); COMPLETED→SUCCESS, FAILED→FAILED, everything else→
   PROCESSING), and `GET /me/accounts/{accountId}/transactions` — the customer
   STATEMENT, sourced from the CORE (not our ledger: an account also accrues
   interest postings, fees and teller corrections that never crossed this
   middleware, and a statement missing those would not reconcile against the
   balance on `/me/accounts`; the trade-off is that a movement parked as
   UNKNOWN has no confirmed core entry and appears only once it reconciles).
   Ownership checked against the core's account list BEFORE the fetch; page
   size hard-capped at 100 (`TransactionHistoryQuery` refuses limit < 1, and
   the port contract forbids an unbounded query). **Fineract gotcha — the
   statement envelope, verified against a live cell (2026-07-31), NOT
   assumed:** `/transactions/search` returns a Spring Data page keyed
   `{"total":N,"content":[…],"pageable":{…}}`. It is NOT Fineract's legacy
   `totalFilteredRecords`/`pageItems` wrapper (other endpoints do use that)
   and NOT Spring's default `totalElements`. Modelling it on the legacy names
   with a primitive `long` count 500'd every statement in production, because
   Jackson rejects a whole document over one absent primitive. All three key
   spellings are now aliased and the count is boxed; **`totalCount` is
   nullable end-to-end — null is UNKNOWN, never zero**, and clients page until
   a page returns fewer than `limit` entries. Dates arrive as `[yyyy,m,d]`
   ARRAYS from the legacy Gson serializer (ISO strings also accepted).
   `CoreBankingExceptionHandler`:
   `CoreClientException` → 422
   `core_rejected` (upstream wording allowed), auth/server/transient → 502/503
   generic (ops detail stays in logs). Amounts cross the API in MINOR units.
   Contract pinned by `RegisterFlowIntegrationTest` +
   `TransactionFlowIntegrationTest` (stub port, real Postgres).

4. **Fineract cell hardening — code half DONE; ops half is the runbook.**
   The fork (`MpofuSlim/fineract`) carries the `Release InnBucks cell image`
   workflow: pushing an immutable `innbucks-cell-N` tag builds via Jib,
   gates on Trivy (CRITICAL/HIGH, `--ignore-unfixed`, governed
   `.trivyignore`), pushes `ghcr.io/mpofuslim/fineract:sha-<commit>` +
   the cell tag, and attests provenance. This repo carries the cell kit:
   `deploy/fineract/docker-compose.yml` (own Postgres, all secrets required
   from `.env`, UTC tenant timezone, no `test` profile / no JDWP agent,
   loopback-only 8443 + the external `innbucks-cell-shared` network that the
   root compose's middleware service also joins), `provision-cell.sh`
   (idempotent: admin rotation, cell currency, zero-interest wallet savings
   product, TWO least-privilege roles/AppUsers with permission codes
   verified against the build, client Constitution / Main Business Line code
   values for the CBS console's Entity form — step 4d, NON-fatal because the
   middleware creates only PERSON clients and a back-office dropdown must not
   fail a cell stand-up, and its defaults are ZW-shaped so set
   `CLIENT_CONSTITUTIONS`/`CLIENT_BUSINESS_LINES` per market — optional smoke
   driving the adapter's exact call sequence), and
   **`docs/fineract-cell-runbook.md`** — THE procedure
   for standing up/upgrading a cell (incl. the internal-CA TLS recipe and
   the middleware truststore wiring; `deploy/fineract/ssl/` is gitignored).
   Remaining is purely operator work on the box: run the runbook.
5. **Auth completion — DONE.** Four pieces, plus the WhatsApp fallback:
   - **SMS via the InnBucks notification gateway** (platform decision: the
     SAME gateway the ticketing fleet sends through — NOT Africa's Talking).
     `notify/` package: `NotificationGatewayClient` ported from ticketing's
     proven client (`POST /auth/third-party` → cached bearer until JWT `exp`
     −30s, `X-Api-Key` on every call, one forced refresh-and-replay on 401,
     SMS body `{message, reference, destinationMsisdn}` with auto
     `IBMW-SMS-<uuid>` refs, `SmsTextSanitizer` GSM whitelist on the SMS
     path, EMAIL surface (`{subject, message, reference, destinationEmail}`,
     subject-only sanitised) pinned now for later use; never logs
     bodies/MSISDNs; HTTP/1.1 pinned for wire parity with ticketing).
     Selection is ONE explicit switch — `innbucks.notify.provider`
     (`console` | `innbucks-gateway`) in `NotificationConfig`, NOT
     `@ConditionalOnMissingBean` (bean-order roulette). Gateway mode makes
     the four `NOTIFY_API_*` values boot-required; console mode under a
     deployment profile logs a page-worthy ERROR (UAT-legal, go-live
     blocker). Delivery failure rolls back the OTP tx (no challenge row
     without a dispatched SMS) → 503 `sms_delivery_failed`. Metric:
     `innbucks.sms.sent{outcome=success|whatsapp_fallback|failure}` — alert
     on failures, treat rising fallback as an SMS-provider incident.
   - **WhatsApp fallback** (`WHATSAPP_FALLBACK_ENABLED` + gateway URL/key):
     when the SMS gateway rejects/times out, the ORIGINAL body (no GSM
     transliteration — WhatsApp renders Unicode) rides the external WhatsApp
     gateway (`POST /api/messages/custom-notification`, lowercase
     `x-api-key`, 1600-char cap — same wire shape as ticketing's client).
     Delivery fails only when every configured channel fails.
   - **Device binding at refresh rotation**: the stored `device_hash` must
     equal the presented one (constant-time; null stored = mismatch, fail
     closed). Mismatch = theft signal → whole family revoked + audit
     `REFRESH_DEVICE_MISMATCH`, surfaced as the GENERIC `refresh_invalid`
     401 (no oracle for the attacker).
   - **Step-up OTP on high-value movement**: withdraw/transfer at/above the
     caller's KYC-tier threshold (`innbucks.stepup.thresholds.*`, MINOR
     units) 403s `step_up_required` + a server-computed `txnFp`
     (SHA-256 over customerId‖type‖from‖to‖amount‖currency, 0x1F-joined —
     customer id inside means cross-customer replay is impossible).
     AUTHENTICATED `/auth/step-up/request|verify` send the OTP to the
     LOGGED-IN customer's registered MSISDN and mint a verification token
     with purpose `STEP_UP` + `txn_fp` claim; the movement accepts it only
     on fingerprint match (constant-time) and consumes the `jti`
     (single-use, same `consumed_verification_token` store as PIN flows) —
     all BEFORE the idempotency claim, so refusal leaves zero state. The
     public `/auth/otp/*` endpoints REJECT purpose STEP_UP (else an
     unauthenticated caller could SMS approval codes to arbitrary numbers /
     mint unbound tokens). Deposits never step up. Crash-retry caveat
     (accepted): a same-key retry of an executed movement needs one fresh
     approval — the alternative (not consuming) lets a captured token
     re-fire the transfer with fresh keys for its whole TTL.
   - **RS256 minting with `kid`, staged like ticketing's migration**:
     `JWT_SIGNING_ALG` (HS256 default) + optional `JWT_PRIVATE_KEY` /
     `JWT_PUBLIC_KEY` / `JWT_KEY_ID` (PEM, `\n`-escape tolerant via
     `PemKeys`). The decoder selects the verification key by the TOKEN'S own
     `alg` header (RS256 → public key, HS256 → secret; anything else
     rejected). Order: (1) `JWT_PUBLIC_KEY` everywhere + roll, (2) flip
     minting, (3) ≥ max TTL later retire the HS256 secret. Misconfig fails
     at boot, never per-request. Verification tokens stay HS256 on their own
     separate key.
   Contract pinned by `NotificationGatewayClientContractTest`,
   `WhatsAppFallbackContractTest`, `JwtIssuerRs256Test`,
   `StepUpFlowIntegrationTest`, and the device-mismatch case in
   `LoginFlowIntegrationTest`.

6. **Release workflow — DONE.** `.github/workflows/release.yml`, mirroring the
   ticketing fleet's supply chain: a `test` job GATES the image build (no
   artifact is ever attested from a red commit), build with `load: true,
   push: false` so Trivy scans the exact bytes CRITICAL/HIGH before they can
   leave the runner, then push with `provenance: mode=max` + `sbom: true` +
   a GitHub-native signed build-provenance attestation. Tags `:latest` (main
   only), `:sha-<full-commit>`, and `v*` tags. `.trivyignore` is a governed
   waiver list (owner + per-CVE reasoning + review date); the Dockerfile
   runtime stage does a blanket `apk --no-cache upgrade` inside the pinned
   Alpine branch — a named-package list made the gate a tripwire on every new
   advisory — plus hard `apk info -e '<pkg>>=<fixed>'` floor assertions, but
   ONLY for CVEs whose fix exists in the branch (an assertion Alpine can't
   satisfy fails every build forever; verify with `apk policy <pkg>` before
   adding one). See the deploy commands under **Environments** above.

7. **Transaction alerts (customer SMS on every money movement) — DONE.**
   `notify/txn`: one SMS after money ACTUALLY moves — deposit, withdrawal,
   outgoing transfer, and the incoming leg when the destination account
   belongs to a customer of this cell. Rides the same `SmsSender` (so the
   same WhatsApp fallback) as OTP.
   - **The seam is `LedgerService.transition()`**, which publishes
     `SettledMovementEvent` for COMPLETED/FAILED only. Being at the one
     chokepoint means a row the reconciler resolves an hour later alerts
     exactly like one that completed on the request thread, and a future
     caller cannot forget to. SUBMITTED and UNKNOWN are deliberately SILENT —
     UNKNOWN is precisely the state where any message we could send might be
     false.
   - **Subordinate to the movement, structurally.** The listener is
     `@TransactionalEventListener(AFTER_COMMIT)` (nobody is told about a
     movement that then rolled back) `@Async` on a bounded pool (a slow
     gateway must not add latency to the money path), and NOTHING escapes it
     — an exception from an after-commit callback propagates to the caller of
     `commit()`, which would make a dead SMS gateway look like a failed
     deposit. This is the deliberate OPPOSITE of the OTP path, where delivery
     failure DOES roll the challenge back. Fire-and-forget: a crash between
     commit and send loses the message; the ledger + statement are the record.
   - **Incoming-transfer recipients are resolved POSITIVELY.** The
     `<customerUuid>:wallet` convention only yields a CANDIDATE; it is then
     confirmed against the core's own account list for that customer before
     anything is sent. Acting on the convention alone would send one
     customer's amounts to another customer's phone. A core without
     `CLIENT_ASSIGNED_EXTERNAL_ID` will need a persisted account→customer
     map here, not a convention.
   - **Copy constraints are load-bearing.** The gateway 400s on
     ``! : / ? " * ;`` — hence `Ref.` and `14.05`, never `Ref:`/`14:05`;
     `TransactionMessageComposerTest` asserts every template round-trips
     `SmsTextSanitizer` unchanged, so a colon creeping back in fails the
     build. **The recipient's leg of a transfer names the SENDER**
     (`from T.Mpofu` — initial + surname, resolved from the core, best-effort
     with the account phrase as fallback) because four digits of an account
     they have never seen identify nobody; the sender's own leg keeps the
     account, having just seen the recipient's name on the confirm screen.
     Note this is the MIRROR of `/accounts/lookup`'s masking (`Tariro M.`)
     and deliberately so — that endpoint is an oracle anyone can query by
     number, this one only reaches someone who was actually paid. A rendered
     name is capped at the length of `account ending 0000`, so naming can
     never push a message into a second segment. Messages are budgeted to ONE 160-char GSM-7 segment: a narration
     that would overflow is DROPPED (the alert still sends) rather than
     silently doubling the per-transaction SMS bill. Accounts are masked to
     their last four identifying characters, matching the `accountId` tail
     the app already shows. Timestamps render in the deployment country's
     civil zone (`Country.zoneId()`, ZW → Africa/Harare) — storage and logs
     stay UTC.
   - **Cost + config.** `innbucks.notify.transactions.*` (`TXN_ALERTS_*`):
     `enabled` (default on), `notify-on-failure` (default OFF — the app
     already renders the error and a core outage would SMS everyone who
     tried; a transfer recipient is NEVER told about a failure), zone,
     length budgets. This multiplies SMS spend by transaction volume — a
     transfer between two of our customers is two messages. Watch
     `innbucks.transaction.notifications{type,leg,outcome}`;
     `outcome=failed` rising means customers are moving money blind, and
     `outcome=dropped` means the queue saturated.
   Contract pinned by `TransactionMessageComposerTest` (exact wording),
   `TransactionNotifierTest` (routing + guard rails) and the
   `aCompletedDepositAlertsTheCustomer` / silent-on-UNKNOWN cases in
   `TransactionFlowIntegrationTest` — the last of which is the only proof the
   after-commit + async wiring actually fires in a real context.

8. **Core-initiated movement alerts (Fineract webhook) — DONE.** A teller/
   admin posting money directly in the core sends no SMS through slice 7's
   ledger seam (no ledger row → no event — found in staging when an admin
   deposit stayed silent). Closed with the core talking BACK to us:
   - **`CoreMovementListener` SPI in the port module** (the inverse of
     `CoreBankingPort`): adapters translate their core's event wire format
     into `CoreMovementObserved` and invoke it; nothing downstream learns a
     core-specific field. `CoreMovementAlertService` implements it.
   - **Fineract side**: `provision-cell.sh` (step 4c, needs
     `CORE_EVENTS_TOKEN`) registers a Web hook on SAVINGSACCOUNT
     DEPOSIT/WITHDRAWAL pointing at
     `/internal/core-events/fineract/{token}/` over the PRIVATE cell network
     (trailing slash REQUIRED — Fineract's Retrofit client rejects a
     slash-less Payload URL and posts the slashed form; the controller maps
     both spellings).
     Fineract's Web hook can set no auth header, so the token rides the URL
     and IS the auth (constant-time compare; wrong/absent token → 404, not
     401 — don't confirm the endpoint exists). Three-files-must-agree:
     controller token + SecurityConfig permitAll + nginx
     `location /middleware/internal/ { return 404; }` (runbook) —
     springdoc `paths-to-exclude` keeps it out of Swagger.
   - **The hook body is a TRIGGER, never a source of truth**: it carries the
     pre-validation request, so the only fields read are the two numeric ids,
     and everything customer-facing is POSITIVELY re-read from Fineract via
     the read credential (`findSavingsById` + `findSavingsTransactionById`).
     A lying payload cannot reach a customer.
   - **Dedup by reference**: our movements attach the ledger `external_ref`
     to the core transaction; the hook echoes it back; a ref found in our
     ledger = already alerted via the ledger seam → dropped
     (`outcome=deduped_ours`), or every app deposit would SMS twice.
     Ownership is still POSITIVELY confirmed against the core's account list
     (naming convention = candidate only), reversed transactions are silent,
     and unprovable ownership means NO message, never a guess.
   - **The hook is answered BEFORE the work runs.** The token check and the
     cheap envelope filter are inline; the re-reads, ownership listing, name
     resolution and SMS ride a separate bounded pool (`coreEventExecutor`,
     `innbucks.core.events{outcome=accepted|ignored|dropped|failed}`). Inline,
     one hook parked a Tomcat thread across that whole chain, and a teller
     batch held several at once against the same core serving customers.
     Deliberately a SEPARATE pool from the ledger seam's — different latency
     shapes, and a teller batch must not starve app-initiated alerts. The pool
     drains on graceful shutdown; an event lost to a hard kill is never
     retried (Fineract's hook dispatch is fire-and-forget with no retry to
     drive), which is the accepted cost of not holding a request thread.
   - `FINERACT_CORE_EVENTS_TOKEN` blank = webhook disabled (boot warns) — a
     cell upgrades with zero config change and simply keeps the old gap
     until the operator provisions the token + re-runs provision-cell.sh.
   Contract pinned by `FineractCoreEventControllerTest` (WireMock, 10 cases
   incl. wrong-token 404 + re-read verification), `CoreMovementAlertServiceTest`
   (dedup, ownership, never-throw) and the observed-copy cases in
   `TransactionMessageComposerTest`.

9. **Credential-spray detection — DONE.** See the security invariant above.
   The gap it closes was found by auditing the rate-limiting story end to end:
   every existing control is scoped to ONE victim or ONE address, and a spray
   defeats both by construction. Two deliberate scoping choices: the block
   covers the AUTH endpoints only (a customer sharing an office NAT with an
   attacker keeps using the token they already hold), and refresh-token
   failures are NOT tracked (a 128-bit random secret is not brute-forceable,
   and replay already triggers whole-family revocation).

   **Still open from the same audit, in priority order** — the first three are
   deployment settings, not code:
   (1) **DONE on the ZW cell** — `RATE_LIMIT_TRUST_FORWARDED_FOR` /
   `RATE_LIMIT_TRUSTED_PROXY_COUNT` are now `true` / `2`, confirmed by the boot
   line `Rate limiting resolves the client IP from X-Forwarded-For counting 2
   trusted proxy hop(s) from the right` (2026-09-02). **This is configured, not
   yet sound** — that same log line ends "sound ONLY while the origin refuses
   direct traffic", and (2) below is still open, so a spoofed XFF chain still
   defeats it. Re-verify the boot line after any cell that has not been rolled
   since; the ZW value does not travel to other cells. (2) The origin's
   443 is open to the world, so an attacker who finds it skips Cloudflare and
   forges the whole XFF chain — which is what still makes (1) decorative, and
   is now the top item rather than the second. (3)
   `/fineract-provider/**` answers from the public internet. (4) **No rate
   limit on `/transactions/*`** — a stolen token can fire movements as fast as
   the core accepts them, and repeated transfers just under the step-up
   threshold are unthrottled. (5) Velocity/amount limit tables (already
   deferred below) are the real fix for (4).

   **Two auth-concurrency gaps deliberately deferred** (found while making the
   lockout counter atomic; both need the SAME mechanism, so they ship together):
   (a) **concurrent-burst gate** — the lock/backoff gate is still decided on the
   snapshot read BEFORE the Argon2id compare, so K simultaneous requests all
   pass it, all pay the hash and all get a definitive 401 before the counter can
   refuse any of them. The counter is now honest (the account does lock) but the
   ladder's RATE is not enforced *within* a burst. (b) **PIN-changed-mid-login**
   — a login racing a PIN reset can still accept the OLD `pin_hash` it read
   before hashing. Both close with a `SELECT … FOR NO KEY UPDATE` re-read of the
   row inside the settling statement's transaction. It MUST be `FOR NO KEY
   UPDATE`, not `FOR UPDATE`: `refresh_token.customer_id` and
   `ledger_transaction.customer_id` both FK to `customer`, so their INSERTs take
   `FOR KEY SHARE` and a plain `FOR UPDATE` would block refresh-token issuance
   and money movements behind a login. Deferred because it changes `/auth/login`
   from 401 to 429 under concurrency (a mobile-client contract change — confirm
   the app honours `Retry-After` first) and because a blocking row-lock wait
   holds a pooled connection, partly undoing the fix above on the money path.
   Also unfixed and in the same family: `PinService.apply` still computes
   Argon2id inside its own `@Transactional`, so `/auth/pin/{set,reset}` keep the
   connection-hold bug (fixing it drags in the consumed-verification-token
   INSERT, which genuinely needs its transaction).

Next (in order):
10. **Veengu adapter** behind the same port (`V-Tenant`/`V-Access-Token`
   headers, consent-then-execute saga, REVERSAL capability).
   **ON HOLD until the full Veengu API spec is in hand (owner's call,
   2026-07-30)** — do NOT start it from the older specs pinned in
   ticketing-system `docs/api/veengu-*.json`; when the full spec arrives,
   pin it in THIS repo under `docs/api/` and model the adapter against
   that (same trim-aggressively rule as the Fineract DTOs).

## Cell backup / restore

`deploy/backup-cell.sh` takes a point-in-time snapshot of a whole cell before
anything destructive (load test, Fineract upgrade, schema migration);
`deploy/restore-cell.sh` rolls it back. Both kinds of backup are taken on
purpose because they fail differently: **SQL dumps** (`pg_dumpall` per cluster)
survive a Postgres major-version change and can be read with your eyes;
**volume tarballs** restore as a file copy rather than a statement replay, which
is what you want in a hurry, but only into the SAME major version (the cell runs
PG18.3 for Fineract and PG16 for the middleware — the restore script guards the
mismatch explicitly).

The app containers are STOPPED for the dump, deliberately: `pg_dumpall` runs a
separate dump per database and Fineract has TWO (`fineract_tenants` +
`fineract_default`), so a live dump can capture them a second apart and restore
a mismatched pair.

Backups are VERIFIED, not assumed — gzip integrity, non-trivial size, and each
dump must actually mention a table it should. `row-counts-before.txt` is the
baseline the restore diffs against to prove it came back clean.

**A clean restore does NOT trip an audit chain-break, and a break after one is a
REAL finding — investigate it.** (This paragraph previously said the opposite.
It was wrong, and a doc that says "this alert is expected" is worse than no doc:
it trains an operator to dismiss the one signal the chain exists to raise.)

`AuditIntegrityVerifier` holds **no external checkpoint**. It walks `audit_event`
oldest-first straight out of the database, recomputes every `row_hmac` and
`chain_hmac`, and compares the final link against `audit_chain_head` — which
lives in that same database. A restore rewinds the rows and the head pointer
together, so the recomputation still joins up and the verifier reports clean.
Confirmed on the ZW cell: a restore on 2026-08-27 was followed by seven
consecutive nightly runs of `Audit chain verified clean: 186 row(s)`.

The one restore-shaped way to break it is a backup whose `audit_event` and
`audit_chain_head` were captured at different moments — which is exactly why
`backup-cell.sh` stops the app containers before dumping. A break after a
restore therefore points at the backup being torn, not at the restore being
normal. Rotating `AUDIT_HMAC_SECRET` is the other benign cause, and it is
benign only if you actually rotated it.

Deferred (documented, not forgotten): KMS/Secrets Manager custody + rotation
runbook; per-customer namespacing of inbound Idempotency-Keys
(HMAC(customerUuid ‖ key)) before propagation; velocity/amount limit tables;
**offsite/automated** backup scheduling (the scripts are manual and write to the
box — copying them off is still an operator step); ShedLock;
PENDING_VERIFICATION expiry job.
