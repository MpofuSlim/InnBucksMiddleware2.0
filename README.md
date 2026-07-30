# InnBucks Middleware 2.0

Spring Boot middleware between the **InnBucks super-app** (mobile) and the
deployment's **core banking system** — Apache Fineract first, Veengu later —
behind a single `CoreBankingPort` seam. Deployed per **(country, core)** cell.

Successor to `OradianMiddleware`: the proven core-agnostic auth/identity/
idempotency/audit code was ported here; the Oradian integration was retired.

## Modules

| Module | What it is |
|---|---|
| `middleware-corebanking-api` | The `CoreBankingPort` contract — pure Java, zero framework deps |
| `middleware-core` | Customer identity, auth (MSISDN+PIN login, OTP, refresh rotation, JWT), tamper-evident audit chain, idempotency, rate limiting, country/MSISDN SPI |
| `middleware-adapter-fineract` | Fineract implementation of the port (client lands next slice) |
| `middleware-app` | The Spring Boot deployable: config, Flyway, Dockerfile |

## Quick start (dev)

```sh
cp .env.example .env               # fill in values (or keep dev placeholders)
docker compose up -d postgres
set -a; source .env; set +a
./mvnw -pl middleware-app spring-boot:run
# Swagger UI: http://localhost:8090/swagger-ui.html
```

`SPRING_PROFILES_ACTIVE=dev` is required locally — an empty profile set counts
as a deployment and the fail-closed secrets guard will refuse placeholder
secrets by design.

## Build & test

```sh
./mvnw verify        # unit tests + Testcontainers integration tests (needs Docker)
```

See `CLAUDE.md` for the architecture contract, security invariants, and the
slice roadmap.
