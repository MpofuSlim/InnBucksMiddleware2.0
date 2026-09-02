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
| `constitutionId` | integer | code-value id — sole trader, private company, trust… |
| `incorpNumber` | string | company registration number |
| `incorpValidityTillDate` | date string | needs `locale` + `dateFormat` |
| `mainBusinessLineId` | integer | code-value id — sector |
| `remarks` | string | free text |

All optional, but **none of them can be captured at all** while the form is
person-only. If the console never sends `clientNonPersonDetails`, no
`m_client_non_person` row is created and there is nowhere to put a company
registration number later except by editing the record.

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
  "clientNonPersonConstitutionOptions":     [ { "id": 12, "name": "Private Company" } ],
  "clientNonPersonMainBusinessLineOptions": [ { "id": 21, "name": "Retail Trade" } ],
  "officeOptions": [ … ], "staffOptions": [ … ]
}
```

Drive the Legal Form dropdown from `clientLegalFormOptions` rather than
hard-coding "Person"/"Entity" — the ids are what the API wants and they come
from the server.

**If the two `clientNonPerson*` lists come back empty**, the cell has no code
values configured for them yet. They're standard Fineract code lookups named
**`Constitution`** and **`Main Business Line`** (`ClientApiConstants.java:62-63`),
populated under Admin → System → Manage Codes. Render those selects as optional
and hide them when the list is empty rather than blocking submission.

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
    "constitutionId": 12,
    "incorpNumber": "12345/2024",
    "incorpValidityTillDate": "2030-12-31",
    "mainBusinessLineId": 21,
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
- add a **Company Details** section: Constitution, Registration Number,
  Registration Valid Until, Main Business Line, Remarks — all optional, all
  going into `clientNonPersonDetails`
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
