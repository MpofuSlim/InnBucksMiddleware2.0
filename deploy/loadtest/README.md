# Fineract TPS load test

Measures what the cell's Fineract sustains for savings deposits, and — more
usefully — **what limits it**.

Run in order. Each stage is cheap and validates the next one's assumptions.

```sh
cd ~/InnBucksMiddleware2.0/deploy/fineract
export ADMIN_PASSWORD='...' MW_WRITE_PASSWORD='...'

../loadtest/00-discover.sh                              # read-only, seconds
I_KNOW_THIS_IS_STAGING=yes ../loadtest/10-fixtures.sh 200
../loadtest/20-run.sh                                   # smoke: 1 VU, 10 requests
../loadtest/20-run.sh --mode full --rate 50 --max-vus 40
```

**Take a backup first** — `../backup-cell.sh`. This writes a large number of
permanent rows.

## The two things that make a naive TPS test of this system lie

Both were read out of the fork, not assumed.

**Concurrent postings to one account sleep rather than contend.**
`SavingsAccount` carries `@Version` (`SavingsAccount.java:142`). Fineract does
not fail a conflict, it retries it:

```
fineract.retry.instances.executeCommand.max-attempts=3       (application.properties:470)
fineract.retry.instances.executeCommand.wait-duration=1s     (:471)
...enable-exponential-backoff=true, multiplier=2             (:472-473)
```

So a collision costs 1s, then 2s, of waiting. Point 50 virtual users at one
account and you measure ~1 TPS. **Every VU owns a disjoint SLICE of the accounts
file** — `20-run.sh` refuses to start if `--max-vus` exceeds the account count,
and `deposit-load.js` gives VU *n* the slice `[(n-1)·k, n·k)` where
`k = floor(accounts / VUs)`. Slices never overlap, so two concurrent postings
against one account remain structurally impossible.

**Per-deposit cost grows with the account's history.**
`SavingsAccount.recalculateDailyBalances()` (line 1008) walks *every*
transaction on the account on each posting. Fresh accounts overstate
steady-state throughput; a long run on few accounts will visibly decay. That
decay is real production behaviour — report the per-account transaction count
alongside any TPS figure.

This is also why the slice matters. Before it existed, VUs used only the first
`max_vus` entries: a 200-account file was worked by 40 accounts, the other 160
sat at one transaction each, and a single ten-minute run aged its own subject by
~250 transactions per account *while measuring it*. Spreading over the whole
file slows that by a factor of `k`. **Runs from either side of that change are
not directly comparable** — check the per-account history on both before
drawing a line between them.

## The ceiling that is not in this repo

The per-tenant connection pool caps concurrent Fineract writes, and it lives in
the **database**, not in config: `DataSourcePerTenantServiceFactory
.getMaxPoolSize()` (line 113) only honours `fineract.tenant.config.max-pool-size`
when it isn't `-1`, and `-1` is the default (`application.properties:64`). The
effective value is `tenant_server_connections.pool_max_active` in
`fineract_tenants` (`TenantMapper.java:37`).

`00-discover.sh` reads it. Ramp past it and you measure the pool's queue.

## Running the generator on the same box

k6 competes with Fineract and both Postgres instances for the same cores.
`20-run.sh` pins k6 to the top two cores when there are ≥4, samples everyone's
CPU throughout, and reports the generator's peak next to the server's. **If k6
is pegged and Fineract isn't, the number is k6's.** With <4 cores the script
says so and the result is a floor, not a ceiling.

## Metrics

Fineract's Prometheus export is **off by default**
(`application.properties:357`). Without it you get a number and no diagnosis.

Before the baseline run, add to `deploy/fineract/.env`:

```
FINERACT_MANAGEMENT_PROMETHEUS_ENABLED=true
```

then `docker compose up -d --force-recreate fineract`. It's a restart — do it
before the run, not between runs.

**This only works because `deploy/fineract/docker-compose.yml` declares the
variable under the fineract service's `environment:`.** Compose's `.env` file
feeds `${...}` interpolation *in the compose file*; it is not passed into
containers, and the fineract service has no `env_file:`. Adding a key to `.env`
that the compose file never references is silent — the container starts
cleanly, the setting is simply absent. The same commit added `prometheus` to
`FINERACT_MANAGEMENT_ENDPOINT_WEB_EXPOSURE_INCLUDE`, which our compose had
narrowed to `health,info`; with the endpoint unexposed the export flag alone
would still have returned 404.

**Confirm, don't assume** — re-run `00-discover.sh` and check that
`GET /actuator/prometheus` reports `200`. The env var being set is not the
same fact.

**Turn it off after the test.** Fineract runs actuator on the application port
under the application context path (`application.properties:381-382`), so the
endpoint is `/fineract-provider/actuator/prometheus` — inside the prefix that
still answers from the public internet. Left on, it publishes heap, GC, Hikari
pool state and a per-URI request breakdown to anyone who asks.

### Deeper diagnostics

The compose file declares these too, all defaulting to Fineract's own default so
the block is inert until you opt in. Same rule as above: a key not declared in
`docker-compose.yml` cannot be set from `.env` at all.

| `.env` key | Buys you | Cost |
|---|---|---|
| `FINERACT_MANAGEMENT_METRICS_DISTRIBUTION_HTTP_SERVER_REQUESTS=true` | per-URI latency histograms — separates Fineract's own time from network + generator time | needs prometheus on |
| `FINERACT_SERVER_TOMCAT_MBEANREGISTRY_ENABLED=true` | Tomcat thread-pool and queue-depth MBeans | negligible |
| `FINERACT_SAMPLING_ENABLED=true` + `FINERACT_SAMPLED_CLASSES=<FQCNs>` | per-method timings — splits the entity load from the balance walk | adds CGLIB proxies to money-path beans; take it off after |
| `FINERACT_STATEMENT_LOGGING_ENABLED=true` | SQL statement count per request | **logs bind parameters** — account ids and amounts in plaintext. Staging only, clear the logs after |

A mistyped FQCN in `FINERACT_SAMPLED_CLASSES` **fails silently** — check for one
`Sampling is enabled for <class>` line per class you listed.

**Verify the metric exists, not just that the flag is set.** For the histograms,
confirm a bucket series actually appears in `/actuator/prometheus`; the flag
being accepted is not the same fact as the data being emitted.

## Reading the result

`20-run.sh` writes `RESULT.md` with a diagnosis table: generator-bound,
lock-bound, pool-bound, Postgres-bound or Fineract-bound. Quote the
steady-state rate, not the whole-run average — the first minute is warmup (JIT,
pool fill, page cache) and including it understates throughput.

**Check `dropped_iterations` before quoting anything.** The scenario is an open
model (`ramping-arrival-rate`), which avoids coordinated omission *only while k6
can allocate a VU per scheduled iteration*. Past that it DROPS iterations rather
than queueing them, and `preAllocatedVUs`/`maxVUs` are both capped at
`min(max_vus, accounts)`. So once every VU is busy:

```
achieved rate == max_vus ÷ mean iteration time
```

which is an identity, not a measurement. A run with a large drop count measured
**throughput at that concurrency**, not the cell's ceiling, and its latency
distribution is a closed-loop sample. To find the actual knee, ladder
`--max-vus` (10 / 20 / 40 / 60 / 80) and watch where throughput stops rising —
it is often well below the pool size, and running at the knee usually buys much
better latency for the same throughput.

Run the ladder **ascending on fresh account cohorts**, or restore between rungs.
Account history grows with every run regardless of the order you choose, so a
descending ladder puts the longest histories under the lowest-concurrency rungs
and manufactures a "more VUs is better" result out of nothing.

**A saturated pool is not automatically the cause of a plateau.** If Fineract's
CPU is pegged at the same time, connections are being held while Java burns CPU,
and raising `pool_max_active` just adds threads waiting on the same cores.
`RESULT.md` now reports how many samples had a thread waiting, not just the peak
— peak active and peak pending are independent maxima and need never have
co-occurred.

Any TPS figure from this is a statement about **this box in this
configuration** — a single node with Fineract on a 1 GB heap sharing hardware
with three other services. That is not a statement about Fineract in general,
and the difference matters when comparing against a published benchmark.

## Testing status

Executed against the live staging cell on **2026-08-27** — three full ten-minute
ramps plus a smoke, all four artifact sets retained. Measured on a single
8-vCPU box with Fineract, both Postgres instances and the generator co-resident,
`-Xmx1G`, `pool_max_active=40`, TLS on, 40 concurrent writers:

| accounts | mean history during run | achieved | median | p95 |
|---|---|---|---|---|
| fresh (×2 runs) | ~131 txns | **16.6/s** | 2.1s | 3.2s |
| aged | ~361 txns | **12.7/s** | 2.8s | 4.2s |

Both fresh runs sat at the same history depth to within half a transaction,
which is why they agreed to within 0.2%. The aged run is the same 40 accounts as
the first, so history is the only variable that changed.

Read those numbers with the concurrency caveat above: `max_vus` was 40 in all
three, so they are throughput **at concurrency 40**, not ceilings. The knee has
not been laddered yet.

The log parsers live in `lib-parse.sh` and have regression coverage against
synthetic logs with known answers. No cell, Docker or network needed:

```sh
./selftest.sh
```

Run it after touching `lib-parse.sh`, the VU-slice arithmetic in
`deposit-load.js`, or the sampler output formats in `20-run.sh`. It also
reproduces the three defects that shipped in earlier versions — a container-name
digit leaking into the CPU figure, the tenants-store pool contaminating the OLTP
pool's gauges, and a whitespace split reading GC as 0.00s — so the tests
document that those were real rather than theoretical. Every one of them was
silent: a wrong number in a table cell, never an error.

The k6 image is pinned by digest, like every other image in the cell. The pinned
digest is the one that produced the 2026-08-27 baseline; moving it invalidates
comparison with those runs, so change it deliberately and say so.

## Cleanup

Fineract clients are not hard-deletable, so test accounts stay. They are tagged
`lt-<runId>-*` and countable:

```sql
SELECT count(*) FROM m_savings_account WHERE external_id LIKE 'lt-%';
```

To remove them properly, restore the backup you took: `../restore-cell.sh`.
