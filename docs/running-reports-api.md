# Running Reports — why "Running Client Listing…" never finishes

**Two bugs, and the second one is the reason you saw nothing at all.**

1. Client Listing requires an **office parameter** the console never sends.
2. When that fails, Fineract answers **403** — and 403 from Fineract does **not**
   mean "not authorised". The console almost certainly treats it as a
   permissions no-op and stays silent.

Read out of our fork (`MpofuSlim/fineract`) and confirmed against the live ZW
cell on 2026-09-02.

---

## Confirmed on the cell

```
GET /v1/runreports/Client%20Listing                  ->  403
GET /v1/runreports/Client%20Listing?R_officeId=1     ->  200
```

Same user (`mifos`, `ALL_FUNCTIONS`), same report, same permissions. The only
difference is the parameter.

---

## Bug 1 — the missing parameter

Client Listing's SQL ends with:

```sql
WHERE o.id = '${officeId}'
```

and `stretchy_report_parameter` wires it to parameter 5, `OfficeIdSelectOne`
(variable `officeId`). `ReadReportingServiceImpl.getSQLtoRun` (`:128-134`) only
substitutes placeholders it was **given** — there is no default and no error for
a missing one. With no parameters, `${officeId}` reaches Postgres literally.

Your own dialog already hints at this: *"This report may require parameters
(dates, office, currency)… Parameter selection will be captured on the run
screen."* That run screen doesn't exist yet.

### The `R_` prefix is mandatory

`AbstractReportingProcessService.getReportParams` (`:36-47`) collects **only**
query params beginning `R_`, strips the prefix, and wraps the remainder:

```
R_officeId   →   ${officeId}
```

Anything else is **silently ignored**. `?officeId=1` looks right and does
nothing.

---

## Bug 2 — 403 does not mean forbidden

This is the part that turns a visible error into a silent hang.

The unsubstituted `${officeId}` makes Postgres compare a string literal to a
`bigint` column. That raises a data-integrity error, and Fineract maps it to
**`Status.FORBIDDEN`** (`PlatformDataIntegrityExceptionMapper:52`).
`PlatformDomainRuleExceptionMapper:53` does the same.

So a `403` from this API can mean any of:

| Cause | Exception |
|---|---|
| Genuine permission denial | `NoAuthorizationException` |
| Business-rule veto | `GeneralPlatformDomainRuleException` |
| **SQL / data-integrity failure** | `PlatformDataIntegrityException` |

A console that reads `403` as "the user isn't allowed to do this" will
correctly-looking-ly say nothing — which is exactly the symptom.

> [!IMPORTANT]
> **Never infer authorisation from an HTTP status alone on this API.** Read the
> body. Fineract returns a JSON envelope whose `errors[].userMessageGlobalisationCode`
> names the real cause. This applies everywhere in the console, not just here.

Note the savings lifecycle errors (`not.in.approved.state` and friends) are
**400**, not 403 — they go through `PlatformApiDataValidationException`. So this
overloading affects data-integrity and domain-rule failures, not the savings
transition errors documented separately.

---

## The API

```
GET /v1/runreports/{reportName}?R_<variable>=<value>[&...]
```

Report names contain spaces — URL-encode them (`Client%20Listing`).
`Fineract-Platform-TenantId: default` on every request.

**Response** is a result set the console renders as a table:

```json
{
  "columnHeaders": [
    { "columnName": "Office/Branch",      "columnType": "text" },
    { "columnName": "Client Account No.", "columnType": "text" },
    { "columnName": "Name",               "columnType": "text" },
    { "columnName": "Status",             "columnType": "text" },
    { "columnName": "Activation",         "columnType": "date" },
    { "columnName": "External Id",        "columnType": "text" }
  ],
  "data": [
    { "row": ["Head Office", "000000012", "Matt Tee", "Active", "2026-08-14", "..."] }
  ]
}
```

Columns are **positional** — `data[].row[i]` lines up with `columnHeaders[i]`.
Don't key on column names; render in the order given.

**Other query params** (`RunreportsApiResource.java:96-116`): `exportCSV=true`,
`output-type=HTML|XLS|CSV|PDF`, `parameterType=true`.

---

## Discovering a report's parameters

**Step 1 — which parameters does this report take?**

`GET /v1/reports` and `GET /v1/reports/{id}` return `reportParameters`
(`ReportData.java:46`). Each entry is:

```json
{ "id": 1, "parameterId": 5, "parameterName": "OfficeIdSelectOne", "reportParameterName": null }
```

**Step 2 — what are the dropdown options?**

Run the *parameter* as a report:

```
GET /v1/runreports/OfficeIdSelectOne?parameterType=true
```

It returns the same `columnHeaders`/`data` shape, where column 0 is the **value**
to submit and column 1 is the **label** to show.

This call **skips the permission check** entirely — `checkUserPermissionForReport`
returns early when `parameterType` is true (`RunreportsApiResource.java:143-151`),
on the reasoning that fetching dropdown values isn't privileged. So it works for
any authenticated user, including ones who can't run the report itself.

### The gap you'll hit: `parameterName` is not the query-param name

`ReportParameterData` exposes only `id`, `parameterId`, `parameterName` and
`reportParameterName` (`ReportParameterData.java:25-31`). **It does not expose
`parameter_variable`** — the piece that becomes `R_officeId`.

So the API tells you the parameter is called `OfficeIdSelectOne`, but not that
you must send `R_officeId`. Two ways to bridge it:

- **If `reportParameterName` is non-null, use that as the variable.** It's the
  per-report override. It's `null` for Client Listing.
- Otherwise you need the `parameter_variable`. Build the map once from the cell:

```sql
SELECT parameter_name, parameter_variable, parameter_label,
       parameter_displayType, parameter_FormatType
FROM stretchy_parameter
ORDER BY parameter_name;
```

Ask the BE to run that and hand you the result — it's a small, stable list
(office, loan officer, currency, fund, product, date ranges) and it also gives
you the **label** and **display type** for rendering each control, neither of
which the API exposes either.

---

## What to build

**A parameter step between "Run Report" and the request.**

1. On "Run Report", fetch the report's `reportParameters`.
2. If empty, run it immediately.
3. Otherwise render one control per parameter, using `parameter_displayType`
   (`select`, `date`, `text`) and `parameter_label` from the map above.
4. Populate every `select` from `?parameterType=true` on that parameter's name.
5. Submit as `R_<variable>`.

**Fix the error path independently.** Even with parameters correct, a failed run
must stop the spinner and show what happened:

- Any non-2xx clears the "Running…" toast.
- Parse `errors[].userMessageGlobalisationCode` from the body rather than
  branching on the status.
- **Do not treat 403 as a silent permissions no-op** anywhere in the console.

That second item is the real defect. The missing parameter is what triggered it,
but a report that fails for any other reason today produces the same silent
hang.

---

## Reproduce it yourself

```sh
cd ~/InnBucksMiddleware2.0/deploy/fineract
read -rs -p 'mifos password: ' PW; echo

# 403 — the data-integrity failure, NOT an auth failure
curl -sS --cacert ./ssl/cell-ca.crt -u "mifos:$PW" \
  -H 'Fineract-Platform-TenantId: default' \
  'https://localhost:8443/fineract-provider/api/v1/runreports/Client%20Listing'

# 200 with the result set
curl -sS --cacert ./ssl/cell-ca.crt -u "mifos:$PW" \
  -H 'Fineract-Platform-TenantId: default' \
  'https://localhost:8443/fineract-provider/api/v1/runreports/Client%20Listing?R_officeId=1'

# the office dropdown values
curl -sS --cacert ./ssl/cell-ca.crt -u "mifos:$PW" \
  -H 'Fineract-Platform-TenantId: default' \
  'https://localhost:8443/fineract-provider/api/v1/runreports/OfficeIdSelectOne?parameterType=true'
```

Drop `-o /dev/null` and read the 403's body — that envelope is what your error
handling should be reading.

---

## Not the middleware's problem

The CBS console talks to Fineract directly and should keep doing so. The
InnBucks middleware has no reporting surface and none should be added to serve
this screen — same rule as the savings-approval and client-legal-form docs.

---

## Source references

| Claim | File |
|---|---|
| Run endpoint, query params | `RunreportsApiResource.java:87-116` |
| `parameterType=true` skips the permission check | `RunreportsApiResource.java:143-151` |
| Only `R_`-prefixed params are collected | `AbstractReportingProcessService.java:36-47` |
| Placeholders substituted only when supplied | `ReadReportingServiceImpl.java:128-142` |
| Client Listing SQL + `${officeId}` | `0003_postgresql_specific_initial_data.xml:481` |
| Client Listing → parameter 5 | `0002_initial_data.xml:13488-13493` |
| `OfficeIdSelectOne` variable = `officeId` | `0002_initial_data.xml:12058-12068` |
| Data-integrity errors map to 403 | `PlatformDataIntegrityExceptionMapper.java:52` |
| Domain-rule vetoes map to 403 | `PlatformDomainRuleExceptionMapper.java:53` |
| Report parameter fields exposed | `ReportParameterData.java:25-31` |
