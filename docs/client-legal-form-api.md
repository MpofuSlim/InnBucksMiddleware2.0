# Create Client — Person vs Entity, and why the form must change

**Short answer: yes, the form is supposed to change, and today it doesn't.**

Fineract models a Person and an Entity as genuinely different shapes — different
name fields, plus a whole block of company-only fields the console never
collects.

Read out of our own fork (`MpofuSlim/fineract`), not the public docs. Every
claim below carries a file:line so you can check it.

---

> [!CAUTION]
> **This does NOT fail loudly, which is why it needs fixing.** Fineract's name
> validation is **not keyed on `legalFormId`** — see
> `ClientDataValidator.java:118-156`. Sending `firstname` + `lastname` with
> `legalFormId: 2` is **accepted**: HTTP 200, client created, no error anywhere.
>
> So the console isn't erroring on Entity — it is **silently writing a company
> into the person-shaped columns**, with the entity block simply absent. Every
> "Entity" client created through the form so far is stored as if it were a
> human with a first and last name, and has no `m_client_non_person` row at all.
> That data is wrong at rest, and nothing will ever surface it.

---

## What actually differs

| | Person (`legalFormId: 1`) | Entity (`legalFormId: 2`) |
|---|---|---|
| Name | `firstname` + `lastname` (+ optional `middlename`) | **`fullname`** — one field |
| Date of birth | `dateOfBirth` — meaningful | not applicable |
| Gender | `genderId` — meaningful | not applicable |
| Company block | — | **`clientNonPersonDetails`** (below) |

**`firstname`/`lastname` and `fullname` are mutually exclusive.** Sending both
is rejected (`ClientDataValidator.java:131-133`,
`validateIndividualNamePartsCannotBeUsedWithFullname`). Sending neither fails
with `.no.name.details.passed` (`:154`).

### The Entity-only block

`clientNonPersonDetails` is a nested object. Its accepted keys, in full
(`ClientApiCollectionConstants.java:35-37`):

| Field | Type | Notes |
|---|---|---|
| `constitutionId` | integer | code-value id — sole trader, private company, trust… **required whenever the block is sent** |
| `incorpNumber` | string | company registration number |
| `incorpValidityTillDate` | date string | needs `locale` + `dateFormat` |
| `mainBusinessLineId` | integer | code-value id — sector |
| `remarks` | string | free text |

**The block as a whole is optional; `constitutionId` inside it is not.**
`extractAndCreateClientNonPerson` skips the block entirely when it's absent or
empty (`ClientWritePlatformServiceJpaRepositoryImpl.java:371`) — so an Entity
with no company details is created cleanly, no row, no error. But the moment
you send the block, it builds a `ClientNonPerson`, and that constructor always
runs `validate()`, which hard-fails on a null constitution
(`ClientNonPerson.java:103-113`):

```json
{"httpStatusCode":"400","userMessageGlobalisationCode":"validation.msg.validation.errors.exist",
 "errors":[{"userMessageGlobalisationCode":"error.msg.clients.constitutionid.is.null",
            "defaultUserMessage":"Constitution ID may not be null","parameterName":"constitutionId"}]}
```

Verified against the ZW cell on 2026-09-02. So **Constitution must be a
required control on the Entity form**, not an optional one — see the warning
under "Where the dropdown options come from" if the cell's list comes back
empty.

None of these fields can be captured at all while the form is person-only. If
the console never sends `clientNonPersonDetails`, no `m_client_non_person` row
is created and there is nowhere to put a company registration number later
except by editing the record — and that repair has its own trap, below.

---

## Where the dropdown options come from

`GET /v1/clients/template` already returns everything the Entity form needs
(`ClientData.java:105-107`):

```json
{
  "clientLegalFormOptions": [
    { "id": 1, "code": "legalFormType.person", "value": "Person" },
    { "id": 2, "code": "legalFormType.entity", "value": "Entity" }
  ],
  "clientNonPersonConstitutionOptions":     [ { "id": 28, "name": "Private Limited Company" } ],
  "clientNonPersonMainBusinessLineOptions": [ { "id": 37, "name": "Retail Trade" } ],
  "officeOptions": [ … ], "staffOptions": [ … ]
}
```

The ZW cell's actual values, as provisioned 2026-09-02 — use these for fixtures,
but **read them from the template at runtime**, never hard-code them: they are
per-cell and a different environment will assign different ids.

| Constitution | | Main Business Line | |
|---|---|---|---|
| 25 | Sole Trader | 33 | Agriculture |
| 26 | Partnership | 34 | Mining |
| 27 | Private Business Corporation | 35 | Manufacturing |
| 28 | Private Limited Company | 36 | Construction |
| 29 | Public Limited Company | 37 | Retail Trade |
| 30 | Co-operative Society | 38 | Wholesale Trade |
| 31 | Trust | 39 | Transport and Logistics |
| 32 | Non-Governmental Organisation | 40 | Hospitality and Tourism |
| | | 41 | Financial Services |
| | | 42 | Professional Services |
| | | 43 | Education |
| | | 44 | Health |
| | | 45 | Information and Communication Technology |
| | | 46 | Other |

Drive the Legal Form dropdown from `clientLegalFormOptions` rather than
hard-coding "Person"/"Entity" — the ids are what the API wants and they come
from the server.

**If the two `clientNonPerson*` lists come back empty**, the cell has no code
values configured for them yet. They're standard Fineract code lookups named
**`Constitution`** and **`Main Business Line`** (`ClientApiConstants.java:62-63`),
populated under Admin → System → Manage Codes.

> [!WARNING]
> **An empty `clientNonPersonConstitutionOptions` blocks the Company Details
> section entirely — it is an operator task, not something to design around.**
> Constitution is required whenever the block is sent (above), so with no code
> values there is no id you can legally submit. Hiding the select and sending
> the rest 400s every time.
>
> Handle it by disabling the whole Company Details section with a message
> naming the fix ("Constitution code values are not configured on this
> environment — add them under Admin → System → Manage Codes"). The Entity
> client itself still creates fine without the block, so registration is not
> blocked — only the company details are.
>
> `mainBusinessLineId` is genuinely optional and can stay hidden when its list
> is empty.
>
> **This was the live case on the ZW cell, and is the default on any fresh
> one.** Both lists came back `[]` there until 2026-09-02, when the codes were
> populated with 8 and 14 values (listed below). A newly provisioned cell ships
> with the two codes present but **empty**, so still build the disabled state —
> otherwise the first person to open the console on a new environment gets a
> form that 400s.
>
> To populate a cell: `GET /v1/codes` to find the id of the code named
> `Constitution` (24 on ZW) or `Main Business Line` (25), then
> `POST /v1/codes/{codeId}/codevalues` per value with `{"name", "position",
> "isActive"}` — that body's whitelist is strict (`name`, `position`,
> `description`, `isActive`, `isMandatory`). Note the **code** ids and the
> **code-value** ids are separate sequences and happen to overlap here: code 25
> is `Main Business Line`, code *value* 25 is `Sole Trader`.

---

## Request bodies

Both are `POST /v1/clients` with `Fineract-Platform-TenantId: default`.

### Person

```json
{
  "officeId": 1,
  "legalFormId": 1,
  "firstname": "Tendai",
  "lastname": "Moyo",
  "mobileNo": "+263770000000",
  "externalId": "optional-ref",
  "active": true,
  "activationDate": "2026-09-02",
  "locale": "en",
  "dateFormat": "yyyy-MM-dd"
}
```

### Entity

```json
{
  "officeId": 1,
  "legalFormId": 2,
  "fullname": "Moyo Holdings (Private) Limited",
  "mobileNo": "+263770000000",
  "externalId": "optional-ref",
  "active": true,
  "activationDate": "2026-09-02",
  "clientNonPersonDetails": {
    "constitutionId": 28,
    "incorpNumber": "12345/2024",
    "incorpValidityTillDate": "2030-12-31",
    "mainBusinessLineId": 37,
    "remarks": "Registered with the Companies Office",
    "locale": "en",
    "dateFormat": "yyyy-MM-dd"
  },
  "locale": "en",
  "dateFormat": "yyyy-MM-dd"
}
```

Note `locale`/`dateFormat` appear **inside** `clientNonPersonDetails` too — that
nested object is validated by its own whitelist, and `incorpValidityTillDate` is
parsed against them there.

---

## Two things that will bite

**Both whitelists are strict.** The outer body is checked against
`CLIENT_CREATE_REQUEST_DATA_PARAMETERS` and the nested object against
`CLIENT_NON_PERSON_CREATE_REQUEST_DATA_PARAMETERS`
(`ClientDataValidator.java:68` and `:76`). **Any key not on the list is a 400**,
not a silently-ignored extra. So build the body per legal form — don't spread a
single form-state object into it, and don't send `firstname: ""` on the Entity
path. An empty string still counts as the parameter being present.

The outer whitelist, in full (`ClientApiCollectionConstants.java:28-33`):
`locale`, `dateFormat`, `groupId`, `accountNo`, `externalId`, `mobileNo`,
`emailAddress`, `firstname`, `middlename`, `lastname`, `fullname`, `officeId`,
`active`, `activationDate`, `staffId`, `submittedOnDate`, `savingsProductId`,
`dateOfBirth`, `genderId`, `clientTypeId`, `clientClassificationId`,
`clientNonPersonDetails`, `displayName`, `legalFormId`, `datatables`,
`isStaff`, `familyMembers`, `address`.

**The key is asymmetric between write and read.** You **send** `legalFormId` as
an integer (`ClientDataValidator.java:222-223`, validated `notNull()` and
`inMinMaxRange(1, 2)`), but you **read back** `legalForm` as an object:

```json
"legalForm": { "id": 2, "code": "legalFormType.entity", "value": "Entity" }
```

Branch your read-side rendering on `legalForm.id`, never on `legalForm.value` —
the display string is localisable, the id is not.

---

## What to build

**Make Legal Form drive the form.** It's currently inert — both screenshots
render identically, which is the bug.

On `legalFormId === 1` (Person):
- First Name\*, Last Name\*, optional Middle Name
- optionally Date of Birth, Gender

On `legalFormId === 2` (Entity):
- **replace** the two name inputs with a single **Company Name\*** → `fullname`
- add a **Company Details** section going into `clientNonPersonDetails`:
  **Constitution\*** (required — see above; the whole section is inert without
  it), Registration Number, Registration Valid Until, Main Business Line,
  Remarks. Send the section only when the operator filled something in — omit
  `clientNonPersonDetails` entirely otherwise, rather than sending an empty
  object or one without `constitutionId`
- hide Date of Birth and Gender

**Clear the hidden fields on switch, don't just hide them.** If the operator
types a first name, switches to Entity and submits, a retained `firstname` plus
`fullname` is the one combination Fineract *does* reject
(`validateIndividualNamePartsCannotBeUsedWithFullname`) — the request 400s and
the message won't obviously point at a field they can no longer see.

**Keep the Office and activation behaviour as-is** — `officeId`, `active` and
`activationDate` are identical on both paths, and the existing "Activate client
on creation" toggle already maps correctly.

---

## Existing bad rows

Any client already created as "Entity" through this form is stored with
`firstname`/`lastname` populated and no `m_client_non_person` row. Fixing the
form does not correct them. To find them:

```sql
SELECT id, display_name, firstname, lastname, fullname, legal_form_enum
FROM m_client
WHERE legal_form_enum = 2 AND fullname IS NULL;
```

Whether to correct those is a data decision, not a code one — the update
endpoint (`PUT /v1/clients/{id}`) accepts `fullname`, so they can be repaired
individually once someone decides what each company's real name is.

### Repairing one: `legalFormId` is mandatory alongside `clientNonPersonDetails`

**On update, `clientNonPersonDetails` is silently discarded unless the request
also carries `legalFormId: 2`** — even when the stored client is already
Entity. This was measured on the ZW cell (2026-09-02) with two clients created
in the broken shape, repaired identically except for that one key:

| Request | Result |
|---|---|
| `{legalFormId: 2, fullname, clientNonPersonDetails: {incorpNumber, remarks}}` | **400** — `error.msg.clients.constitutionid.is.null` |
| `{fullname, clientNonPersonDetails: {incorpNumber, remarks}}` | **200**, `changes: {fullname}` only — **no row, no error** |

The 400 is the good outcome: it proves the block was processed and stopped on
the missing Constitution. The 200 is the trap — the company details vanished
with nothing to tell the operator.

The reason is in `updateClient`. Where no `m_client_non_person` row exists yet,
the create branch reads `legalFormId` **from the request body**, not from the
stored entity (`ClientWritePlatformServiceJpaRepositoryImpl.java:665-677`);
absent, `isEntity` stays false and the block is skipped. Where a row *does*
already exist, it is updated normally and `legalFormId` is not needed
(`:631-663`) — but every row this bug produced has no row, so:

> [!IMPORTANT]
> **Always send `legalFormId: 2` in the same body as `clientNonPersonDetails`,
> including on update.** It costs nothing when the row already exists and is
> the difference between a repair and a silent no-op when it doesn't.

**The update is atomic** — the 400 above rolled back the `fullname` change too,
so a failed repair leaves the record exactly as it was. No half-applied state
to clean up.

**A repair does not clear the stale person columns.** `resetDerivedNames` —
which nulls `firstname`/`lastname` for an Entity — sits inside the branch that
runs only when the legal form actually *changes* (`:500-516`), and re-sending
the value it already has is not a change. `deriveDisplayName()` does run on
every update (`:553`), so `display_name` becomes the company name and the
console looks correct while `firstname`/`lastname` sit underneath. Confirmed in
the table after the repair:

```
 id | legal_form_enum | firstname | lastname |          fullname          |        display_name
 44 |               2 | Discard   | TestB    | Repair B (Private) Limited | Repair B (Private) Limited
```

Fineract permits this on purpose — `Client.validateUpdate()` skips name
validation entirely, commented as allowing both while switching between
Individual and Organisation. To actually clear them takes two requests:
`legalFormId: 1` (clears `fullname`, and **deletes any `m_client_non_person`
row**, `:611-628`), then back to `2` with `fullname` + `clientNonPersonDetails`
together. Only do that if someone has decided the stale columns matter — for
the create-form fix they don't arise at all.

---

## Not the middleware's problem

The InnBucks middleware creates Fineract clients only for **individual mobile
customers**, always as Person with `firstname`/`lastname`. Entity clients are a
back-office concept and don't cross the mobile surface at all — so nothing here
should be added to `POST /register` or anywhere else in
`InnBucksMiddleware2.0`. The console talks to Fineract directly; this is a
console-side change only.

---

## Source references

| Claim | File |
|---|---|
| `LegalForm` PERSON(1) / ENTITY(2) | `LegalForm.java:29-30` |
| Name rules; NOT keyed on legalFormId | `ClientDataValidator.java:118-156` |
| firstname/lastname vs fullname mutual exclusion | `ClientDataValidator.java:131-133`, `:293-311` |
| `legalFormId` required, range 1-2 | `ClientDataValidator.java:222-223` |
| Outer + nested whitelists enforced | `ClientDataValidator.java:68`, `:76` |
| Create-request param lists | `ClientApiCollectionConstants.java:28-37` |
| Entity field names | `ClientApiConstants.java:124-129` |
| Constitution / Main Business Line code names | `ClientApiConstants.java:62-63` |
| Template dropdown options | `ClientData.java:105-107` |
| Block skipped when absent/empty; `constitutionId` required when sent | `ClientWritePlatformServiceJpaRepositoryImpl.java:367-399`, `ClientNonPerson.java:103-113` |
| Update: create branch reads `legalFormId` from the request body | `ClientWritePlatformServiceJpaRepositoryImpl.java:665-677` |
| Update: existing row updated without needing `legalFormId` | `ClientWritePlatformServiceJpaRepositoryImpl.java:631-663` |
| Legal-form change creates/deletes the non-person row | `ClientWritePlatformServiceJpaRepositoryImpl.java:611-628` |
| `resetDerivedNames` only on an actual legal-form change | `ClientWritePlatformServiceJpaRepositoryImpl.java:500-516`, `Client.java:707-714` |
| `display_name` re-derived on every update | `ClientWritePlatformServiceJpaRepositoryImpl.java:553`, `Client.java:457-470` |
| Update skips name validation on purpose | `Client.java:305-317` |
| Update whitelists (outer + nested) | `ClientApiCollectionConstants.java:38-48`, `ClientDataValidator.java:364-375` |
