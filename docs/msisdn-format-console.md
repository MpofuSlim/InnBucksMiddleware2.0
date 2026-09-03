# Phone numbers in the console — normalise to +263 on the way in

**The client list currently mixes `0777112356` and `+263777777777`.** Fineract's
`mobileNo` is a free-text `VARCHAR` with no validation and no normalisation, so
whatever the console sends is exactly what is stored and shown.

The rule you should apply already exists in the backend — mirror it rather than
inventing one. Source:
`middleware-core/.../common/msisdn/ZimbabweMsisdnNormalizer.java`.

---

## Why this lands on the console and not on a server

The obvious question first, because it changes how carefully you need to build
this: **nothing server-side will refuse a badly formatted number.** That is not
an oversight to route around — it is the actual state of the two backends in
play, and it makes the console the only guard.

**Fineract does not validate `mobileNo`.** Its entire rule is
`notExceedingLengthOf(50)` (`ClientDataValidator.java:164-168`). No format
check, no per-tenant toggle, nothing configurable. `0723739370` is ten
characters, so it is accepted, stored and displayed exactly as typed.

**The middleware has the rule but is not in this call.** `ZimbabweMsisdnNormalizer`
guards registration, login, OTP and recipient lookup — every path the *mobile
app* takes. The console posts straight to Fineract, so none of that code runs.
"The backend" for this screen is Fineract, and Fineract has no opinion on phone
numbers.

**Nothing reads the value back, either.** The middleware writes `mobileNo` once
when it creates a client and never reads it again — `CustomerProfile` does not
even carry a phone field, and recipient lookup keys on the middleware's own
`customer` table. So a wrong number here is back-office metadata that reads and
searches badly; it is not something the system makes decisions on.

That last point is why front-end validation is an acceptable answer here rather
than a compromise. Be clear-eyed that it **is** bypassable — anyone with the
admin credential and `curl`, or the bulk-import spreadsheet, writes whatever
they like. We are accepting that because the field is not load-bearing.

> [!IMPORTANT]
> **If `mobileNo` ever becomes something the system reads rather than displays,
> this answer stops holding** and the rule has to move server-side. Flag it
> rather than assuming the console can keep carrying it alone.

---

## Why it is worth fixing beyond looking untidy

**Search misses.** An operator who searches `+263777112356` will not find the
row stored as `0777112356`, and vice-versa. Same human, same number, two
spellings, and the console has no way to know they are the same. That is the
concrete operational cost — not the ragged column.

**Nothing is currently failing**, per the section above — transfers don't touch
this field. So this is worth doing for search and hygiene, not as an incident
fix. Size the work accordingly.

---

## The canonical rule

**Canonical form is E.164: `+263` followed by `7`, then one of `1 3 7 8`, then
7 digits.** Total 13 characters, no spaces.

```
^\+2637[1378][0-9]{7}$
```

Accept these three input shapes and convert:

| Operator types | Store |
|---|---|
| `+263771234567` | `+263771234567` (unchanged) |
| `263771234567` (12 digits) | `+263771234567` |
| `0771234567` (10 digits) | `+263771234567` |

Strip formatting characters before matching — spaces, hyphens, dots and
parentheses are all fine to type. Anything else is a hard reject.

### Reject, don't repair

Two rules the backend deliberately enforces, and the console should too:

**Letters or stray symbols fail the input outright.** Don't silently strip them.
A number with a letter in it is user error or something worse, and quietly
deleting characters turns a typo into a plausible-looking wrong number.

**Only mobile prefixes are valid** — `71` NetOne, `73` Telecel, `77` and `78`
Econet. That list is exhaustive and confirmed (2026-09-03); anything else,
including `72`, is not an allocated ZW mobile range. Landlines and short codes
are rejected too, because these numbers exist to receive OTPs and step-up
approvals; a landline is a customer record that can never complete an app
registration.

### Reference implementation

```js
const MOBILE = /^\+2637[1378]\d{7}$/;
const ALLOWED = /^[0-9+\s\-.()]+$/;

/** Returns canonical E.164, or null if the input is not a valid ZW mobile. */
export function normaliseMsisdn(input) {
  if (!input || !input.trim()) return null;
  if (!ALLOWED.test(input)) return null;          // letters/symbols: reject
  const d = input.replace(/[^0-9+]/g, '');

  let candidate;
  if (d.startsWith('+263'))                   candidate = d;
  else if (d.startsWith('263') && d.length === 12) candidate = '+' + d;
  else if (d.startsWith('0')   && d.length === 10) candidate = '+263' + d.slice(1);
  else return null;

  return MOBILE.test(candidate) ? candidate : null;
}
```

That mirrors `ZimbabweMsisdnNormalizer.normalize` branch for branch. If the two
ever diverge, the Java one is correct — it is what the app's registration,
login, OTP and recipient-lookup paths all run on.

---

## Where to apply it

**On submit — Create Client and Edit Client.** Normalise `mobileNo` before it
goes into the request body. Store the canonical form; never post what was typed.

**On the field, as feedback.** Normalise on blur so the operator sees
`+263771234567` appear while they are still looking at the field, and show a
validation error for a number that does not resolve. Silently rewriting on
submit is worse than showing them the change.

**On search.** Run the operator's query through the same function before
matching, so typing either spelling finds the row. Fall back to a raw substring
match when the query does not normalise — partial numbers still need to work.

**On display — nothing to do** once stored values are canonical. Don't format
at render time: it papers over the inconsistent data instead of fixing it, and
the column stops matching what a search or a copy-paste produces. If you want
visual grouping (`+263 77 123 4567`), render it, but keep the stored value and
the copy target as the unspaced canonical string.

`mobileNo` is optional in Fineract — a blank stays blank, and that is fine.
Only validate what was actually entered.

---

## The rows that are already wrong

Formatting the form does not fix history. To see them:

```sql
SELECT id, display_name, mobile_no
FROM m_client
WHERE mobile_no IS NOT NULL
  AND mobile_no <> ''
  AND mobile_no !~ '^\+2637[1378][0-9]{7}$'
ORDER BY id;
```

The `0…` rows convert mechanically and could be backfilled:

```sql
-- Review the SELECT above first. This is a data change; take a backup.
UPDATE m_client
SET mobile_no = '+263' || substring(mobile_no from 2)
WHERE mobile_no ~ '^07[1378][0-9]{7}$';
```

Deliberately narrow: it only touches numbers that are unambiguously a local-form
valid mobile. Anything else stays untouched for a human to look at.

> [!NOTE]
> **`0723739370` (client "Test Tadiwa rade Paka") is deliberately left behind**
> by that `UPDATE` — prefix `72` is not allocated to a Zimbabwean mobile
> operator (confirmed 2026-09-03), so there is no correct `+263` form to
> convert it to. The row is test data; delete it or leave it, but do not
> "repair" it into a number that was never real.
>
> This was checked as a possible backend gap and is not one:
> `ZimbabweMsisdnNormalizer`'s prefix set is right, and the middleware is
> correct to reject that number on registration and login.

---

## Not the middleware's problem — with one asterisk

The CBS console talks to Fineract directly and this is a console-side change;
no middleware endpoint is involved and none should be added.

The asterisk: unlike the other console docs, the **rule** here is owned by the
backend. `ZimbabweMsisdnNormalizer` is what every customer-facing path already
enforces, so a console that accepts numbers the middleware would reject creates
records the app can never match. Copy the rule; don't fork it.

---

## Source references

| Claim | File |
|---|---|
| Canonical `+2637[1378]…` pattern, three accepted shapes | `ZimbabweMsisdnNormalizer.java:22-59` |
| Letters/symbols rejected rather than stripped | `ZimbabweMsisdnNormalizer.java:27,39-41` |
| Mobile-only prefixes and why | `ZimbabweMsisdnNormalizer.java:8-17` |
| Registration normalises before writing to the core | `RegisterService.java:77` |
| Recipient lookup keys on our `customer` table, not Fineract's `mobileNo` | `RecipientLookupService.java:103-107` |
| `mobileNo` sent to Fineract verbatim | `FineractClient.java:164-170` |
| Fineract's only `mobileNo` rule is a 50-char length cap | `ClientDataValidator.java:164-168` |
| The middleware writes `mobileNo` and never reads it back | `FineractClient.java:164-170`, `CustomerProfile.java` |
