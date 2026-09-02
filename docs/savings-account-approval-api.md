# Savings Account Approval — API contract for the CBS console

**Short answer: yes, this is fully supported.** Fineract has had the whole
approve → activate lifecycle since forever; the CBS console simply isn't
calling it yet. No backend work is required — this is a frontend gap.

Everything below was read out of our own fork (`MpofuSlim/fineract`), not from
the public Fineract docs, which are wrong in places. File and line references
are included so you can check any claim.

---

## Which backend serves this

**The CBS console talks to Fineract directly** — it is a back-office app
authenticating as `mifos`. That is the API documented here.

> [!IMPORTANT]
> **The InnBucks middleware (`InnBucksMiddleware2.0`) does NOT expose these
> endpoints and should not be used for this.** It is the *mobile super-app*
> backend: `/register`, `/me/*`, `/transactions/*`. It runs approve+activate
> internally as part of its wallet-creation saga, but it has no admin surface
> and no concept of a back-office approver. Do not add approval to it to serve
> this screen — the console is a Fineract client, and going through the
> middleware would put a customer-scoped JWT in front of an operator action.

Base URL: `https://<cell-host>/fineract-provider/api`
Required on every request: `Fineract-Platform-TenantId: default`

---

## The lifecycle

`SavingsAccountStatusType.java:29-39` — the numeric codes are what the API
returns, so branch on `status.id`, never on the display string.

| Code | Status | Console shows |
|---|---|---|
| 100 | `SUBMITTED_AND_PENDING_APPROVAL` | Submitted and pending approval |
| 200 | `APPROVED` | Approved |
| 300 | `ACTIVE` | Active |
| 400 | `WITHDRAWN_BY_APPLICANT` | Withdrawn by applicant |
| 500 | `REJECTED` | Rejected |
| 600 | `CLOSED` | Closed |
| 700 | `PRE_MATURE_CLOSURE` | Premature closed |
| 800 | `MATURED` | Matured |

```
                    ┌─────────── reject ──────────► REJECTED (500)
                    │
SUBMITTED (100) ────┼── withdrawnByApplicant ─────► WITHDRAWN (400)
      ▲             │
      │             └─── approve ──► APPROVED (200) ── activate ──► ACTIVE (300)
      │                                    │
      └────────── undoapproval ────────────┘
```

**Accounts in state 100 hold no money and accept no transactions.** That's why
every one of those rows in your screenshot shows `$0.00` — they are
applications, not accounts. `Test Savings ZiG #000000017` and
`Test Savings #000000016` both need approve **then** activate before a deposit
will work.

---

## The endpoints

All four are the same shape: `POST` to the account, with the action in a
`?command=` query parameter. `SavingsAccountsApiResource.java:509-524`.

```
POST /v1/savingsaccounts/{accountId}?command={approve|activate|reject|withdrawnByApplicant|undoapproval}
```

An external-id variant exists if you'd rather not hold numeric ids
(`SavingsAccountsApiResource.java:352` — note the path segment):

```
POST /v1/savingsaccounts/external-id/{externalId}?command=approve
```

> Colons in an externalId must be percent-encoded — our wallet accounts are
> named `<uuid>:wallet`, which goes on the wire as `<uuid>%3Awallet`.

### 1. Approve

```http
POST /v1/savingsaccounts/17?command=approve
Content-Type: application/json
Fineract-Platform-TenantId: default

{
  "approvedOnDate": "2026-08-27",
  "note": "Approved by operations",
  "locale": "en",
  "dateFormat": "yyyy-MM-dd"
}
```

`approvedOnDate` is **required**. `note` is optional, max 1000 chars.
(`SavingsAccountApplicationTransitionApiJsonValidator.java:50-72`)

### 2. Activate

```http
POST /v1/savingsaccounts/17?command=activate

{
  "activatedOnDate": "2026-08-27",
  "locale": "en",
  "dateFormat": "yyyy-MM-dd"
}
```

> [!WARNING]
> **Activate accepts NO `note` field.** The whitelist is exactly
> `locale`, `dateFormat`, `activatedOnDate`
> (`SavingsAccountConstant.java:58-59`). Sending `note` here returns **400**,
> even though approve and reject both accept it. This is the single most likely
> thing to trip up a shared "transition" component in the UI.

### 3. Reject

```http
POST /v1/savingsaccounts/17?command=reject

{
  "rejectedOnDate": "2026-08-27",
  "note": "Duplicate application",
  "locale": "en",
  "dateFormat": "yyyy-MM-dd"
}
```

### 4. Withdrawn by applicant

```http
POST /v1/savingsaccounts/17?command=withdrawnByApplicant

{
  "withdrawnOnDate": "2026-08-27",
  "note": "Customer changed their mind",
  "locale": "en",
  "dateFormat": "yyyy-MM-dd"
}
```

The command name is **case-insensitive** but spelling matters —
`withdrawnByApplicant`, not `withdraw` (`SavingsAccountsApiResource.java:397`).

### 5. Undo approval

```http
POST /v1/savingsaccounts/17?command=undoapproval

{ "note": "Approved in error" }
```

> [!WARNING]
> **Undo approval accepts ONLY `note`** — no `locale`, no `dateFormat`, no date
> (`SavingsAccountApplicationTransitionApiJsonValidator.java:127`). If your HTTP
> layer injects `locale`/`dateFormat` into every request by default, this call
> will 400. Send `{}` or `{"note":"..."}` and nothing else.

---

## The parameter whitelist is strict — this will bite you

Every transition runs `checkForUnsupportedParameters` against a fixed field set.
**Any field not on the list is a 400, not a silently-ignored extra.**

| Command | Accepted fields — nothing else |
|---|---|
| `approve` | `approvedOnDate`, `note`, `locale`, `dateFormat` |
| `activate` | `activatedOnDate`, `locale`, `dateFormat` |
| `reject` | `rejectedOnDate`, `note`, `locale`, `dateFormat` |
| `withdrawnByApplicant` | `withdrawnOnDate`, `note`, `locale`, `dateFormat` |
| `undoapproval` | `note` |

So: build the body per command. Do **not** spread a shared form object into it,
and do not send `id`, `accountId`, `clientId`, `productId` or anything you
happen to have in scope — all of those are 400s.

---

## Dates: asymmetric, and this catches everyone

**Writing** — send a **string**, and `locale` + `dateFormat` must accompany it.
`dateFormat` is a Java pattern; use `yyyy-MM-dd` and format the date yourself
rather than sending an ISO timestamp.

**Reading** — Fineract serialises with Gson, and `LocalDate` goes through
`LocalDateAdapter`, which emits an **array**:

```json
"approvedOnDate": [2026, 8, 27]
```

Not `"2026-08-27"`. That's `[year, month, day]` with a **1-based month**, so
`new Date(y, m, d)` in JS is off by one month unless you subtract. This applies
to every date field on the read model, including `submittedOnDate`,
`activatedOnDate` and the ones on your Account Profile panel (which currently
render `—`).

### "Future date" means Fineract's business date, not the browser's

Both approve and activate reject a future date via `DateUtils.isAfterBusinessDate`
(`SavingsAccount.java:2331`, `:2716`). Fineract carries its **own configurable
business date**, which is not necessarily today — on a cell restored from a
backup it is frequently stale. If approval fails with `cannot.be.a.future.date`
on a date that looks perfectly current, the business date is behind. Ops can
check it; the runbook covers this under "The logical business date".

Practical UI consequence: **default the date picker to the account's
`submittedOnDate` or to today, and let the operator change it** — don't hard-code
`new Date()` and assume it will be accepted.

---

## Response shape

`200 OK` with a `CommandProcessingResult`. Gson reflects over **fields**, and
drops nulls, so absent keys are normal:

```json
{
  "officeId": 1,
  "clientId": 12,
  "savingsId": 17,
  "resourceId": 17,
  "changes": {
    "status": { "id": 200, "code": "savingsAccountStatusType.approved", "value": "Approved" },
    "locale": "en",
    "dateFormat": "yyyy-MM-dd",
    "approvedOnDate": "2026-08-27"
  }
}
```

`changes.status` is the new state — you can update the row from the response
without re-fetching, though re-fetching the account is safer if the screen shows
derived fields.

> [!CAUTION]
> **A 200 does not always mean it happened.** If maker-checker is enabled for
> `APPROVE_SAVINGSACCOUNT`, Fineract parks the command and returns a
> **success-shaped** body — HTTP 200 with `"rollbackTransaction": true` and
> **no `resourceId`** — while the transaction is rolled back. Treat a response
> with `rollbackTransaction: true` or a missing `resourceId` as "queued for a
> checker", not as done, and tell the operator it's pending in the Checker
> Inbox. Blindly flipping the row to Approved on any 200 will show a state the
> database doesn't have.

---

## Errors

`400` with a `PlatformApiDataValidationException` body. The useful part is
`errors[].userMessageGlobalisationCode`; the trailing segment is what to switch
on.

| Code ends with | Cause | Suggested UI message |
|---|---|---|
| `not.in.submittedandpendingapproval.state` | Approve/reject/withdraw on an account that has moved on | "This application is no longer pending approval." |
| `not.in.approved.state` | Activate on an account that isn't Approved | "Approve the application before activating it." |
| `cannot.be.before.submittal.date` | `approvedOnDate` < `submittedOnDate` | "Approval date can't be before the application date." |
| `cannot.be.before.approval.date` | `activatedOnDate` < `approvedOnDate` | "Activation date can't be before the approval date." |
| `cannot.be.before.client.activation.date` | Date precedes the client's own activation | "Date can't be before the client was activated." |
| `cannot.be.a.future.date` | Date is after Fineract's business date | "Date can't be in the future." |

The first two are **races**, not user error — two operators on the same queue,
or a stale list. Refresh the row and re-render its available actions rather
than showing a hard failure.

Other statuses: `401` bad credentials, `403` missing permission, `404` no such
account.

---

## Permissions

| Command | Permission code |
|---|---|
| approve | `APPROVE_SAVINGSACCOUNT` |
| activate | `ACTIVATE_SAVINGSACCOUNT` |
| reject | `REJECT_SAVINGSACCOUNT` |
| withdrawnByApplicant | `WITHDRAW_SAVINGSACCOUNT` |
| undoapproval | `APPROVALUNDO_SAVINGSACCOUNT` |

`mifos` holds `ALL_FUNCTIONS`, so the console works today with no role changes.
If you later give operators their own least-privilege role, these are the codes
to grant. Each also has a `_CHECKER` variant used only by maker-checker.

**Don't reuse the middleware's credentials here.** `innbucks-mw-write` holds a
deliberately narrow set for the wallet saga, and `innbucks-mw-read` holds no
write permissions at all — an operator action authenticating as either would
blur the audit trail, which is the one thing that identifies who approved what.

---

## What to build

**Row actions, driven by `status.id`:**

| State | Buttons |
|---|---|
| 100 Submitted | **Approve**, Reject, Withdrawn by applicant |
| 200 Approved | **Activate**, Undo approval |
| 300 Active | (existing Deposit / Withdraw) |
| 400/500/600/700 | none — terminal |

Your account detail page already has Deposit / Withdraw / More in the header.
The natural fit is to swap those for **Approve** / **Reject** when
`status.id === 100`, and **Activate** / **Undo approval** when it's `200` —
rather than showing Deposit buttons that can only fail, which is the current
behaviour on `#000000017`.

**A dialog per action** with a date picker (defaulted as described above) and,
where the command allows it, an optional note.

**Approve-and-activate as one button is worth considering** for the wallet
product, since operationally they always happen together — but issue them as
two sequential calls and handle partial failure: if approve succeeds and
activate fails, the account is left in state 200 and the UI must show
**Activate**, not roll back. There is no combined endpoint.

**After any transition, re-fetch the account** rather than trusting local
state — interest, lock-in dates and the balance panel are all derived
server-side.

---

## Quick verification

Before wiring the UI, confirm the whole path against the cell (through an SSH
tunnel; `8443` is loopback-only):

```sh
# 1. approve
curl -sS --cacert ./ssl/cell-ca.crt -u 'mifos:<password>' \
  -H 'Fineract-Platform-TenantId: default' -H 'Content-Type: application/json' \
  -X POST 'https://localhost:8443/fineract-provider/api/v1/savingsaccounts/17?command=approve' \
  -d '{"approvedOnDate":"2026-08-27","locale":"en","dateFormat":"yyyy-MM-dd"}'

# 2. activate  (note: no "note" field)
curl -sS --cacert ./ssl/cell-ca.crt -u 'mifos:<password>' \
  -H 'Fineract-Platform-TenantId: default' -H 'Content-Type: application/json' \
  -X POST 'https://localhost:8443/fineract-provider/api/v1/savingsaccounts/17?command=activate' \
  -d '{"activatedOnDate":"2026-08-27","locale":"en","dateFormat":"yyyy-MM-dd"}'
```

Expect `"status": {"id": 200 …}` then `{"id": 300 …}` in `changes`. A deposit
should then succeed where it previously failed.

---

## Source references

| Claim | File |
|---|---|
| Command dispatch, accepted command names | `SavingsAccountsApiResource.java:509-524` |
| External-id route | `SavingsAccountsApiResource.java:352` |
| approve / reject / withdraw / undo field whitelists | `SavingsAccountApplicationTransitionApiJsonValidator.java:50-145` |
| activate field whitelist | `SavingsAccountConstant.java:58-59` |
| Status codes | `SavingsAccountStatusType.java:29-39` |
| Approval state + date rules | `SavingsAccount.java:2289-2354` |
| Activation state + date rules | `SavingsAccount.java:2642-2726` |
| Response fields | `CommandProcessingResult.java:34-52` |
| Permission codes | `db/changelog/tenant/parts/0002_initial_data.xml:6172` |
