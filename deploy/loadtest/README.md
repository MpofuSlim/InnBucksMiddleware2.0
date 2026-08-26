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
account and you measure ~1 TPS. **Every VU must own a distinct account** —
`20-run.sh` refuses to start if `--max-vus` exceeds the account count.

**Per-deposit cost grows with the account's history.**
`SavingsAccount.recalculateDailyBalances()` (line 1008) walks *every*
transaction on the account on each posting. Fresh accounts overstate
steady-state throughput; a long run on few accounts will visibly decay. That
decay is real production behaviour — report the per-account transaction count
alongside any TPS figure.

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

## Reading the result

`20-run.sh` writes `RESULT.md` with a diagnosis table: generator-bound,
lock-bound, pool-bound, Postgres-bound or Fineract-bound. Quote the
steady-state rate, not the whole-run average — the first minute is warmup (JIT,
pool fill, page cache) and including it understates throughput.

Any TPS figure from this is a statement about **this box in this
configuration** — a single node with Fineract on a 1 GB heap sharing hardware
with three other services. That is not a statement about Fineract in general,
and the difference matters when comparing against a published benchmark.

## Testing status

These scripts pass syntax checks. They have **not** been executed against a live
cell — the smoke stage exists to be their first real run. If the smoke stage
fails, that is the scripts meeting reality, not the cell being broken.

## Cleanup

Fineract clients are not hard-deletable, so test accounts stay. They are tagged
`lt-<runId>-*` and countable:

```sql
SELECT count(*) FROM m_savings_account WHERE external_id LIKE 'lt-%';
```

To remove them properly, restore the backup you took: `../restore-cell.sh`.
