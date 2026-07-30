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

* **Inbound (mobile → middleware)**: customer-facing JWT, HS256 today
  (`JWT_SIGNING_KEY`), RS256-with-`kid` migration is a planned slice.
  Issued by `POST /auth/login` (MSISDN + Argon2id PIN → 10-min access +
  30-day rotating opaque refresh, family revocation on replay).
  `sub` = customer UUID; claims: `country`, `kyc_tier`, `scopes`, `did`,
  `nid_hash`, `auth_time`.
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
* **CI supply chain**: every third-party GitHub Action is pinned to an
  immutable commit SHA with a `# vX.Y.Z` comment. Least-privilege
  `permissions:` per workflow.

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
   `fineract.*` property is boot-required). Contract pinned by standalone
   WireMock tests (22 cases). Gotcha: colon-bearing externalIds
   (`<uuid>:wallet`) hit the wire percent-encoded (`%3A`) — WireMock stub
   URLs must match the encoded form.

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
   PROCESSING). `CoreBankingExceptionHandler`: `CoreClientException` → 422
   `core_rejected` (upstream wording allowed), auth/server/transient → 502/503
   generic (ops detail stays in logs). Amounts cross the API in MINOR units.
   Contract pinned by `RegisterFlowIntegrationTest` +
   `TransactionFlowIntegrationTest` (stub port, real Postgres).

Next (in order):
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
   verified against the build, optional smoke driving the adapter's exact
   call sequence), and **`docs/fineract-cell-runbook.md`** — THE procedure
   for standing up/upgrading a cell (incl. the internal-CA TLS recipe and
   the middleware truststore wiring; `deploy/fineract/ssl/` is gitignored).
   Remaining is purely operator work on the box: run the runbook.
5. **Auth completion** — device-binding enforcement at refresh rotation,
   step-up OTP with `txn_fp`-bound tokens + per-tier thresholds, Africa's
   Talking SMS adapter (go-live gate; alert on delivery failures), RS256
   minting with `kid`.
6. **Veengu adapter** behind the same port (`V-Tenant`/`V-Access-Token`
   headers, consent-then-execute saga, REVERSAL capability). Specs pinned in
   ticketing-system `docs/api/veengu-*.json`.

Deferred (documented, not forgotten): KMS/Secrets Manager custody + rotation
runbook; per-customer namespacing of inbound Idempotency-Keys
(HMAC(customerUuid ‖ key)) before propagation; velocity/amount limit tables;
Postgres backup/DR runbook; ShedLock; PENDING_VERIFICATION expiry job;
release workflow (build → Trivy scan → push → attest, per ticketing's
supply-chain rules).
