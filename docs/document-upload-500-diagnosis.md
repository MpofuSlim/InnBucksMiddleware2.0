# Document upload returns 500 — how to find out why

**Short answer: it is almost certainly not S3, and the 500 is hiding the real
reason on purpose.** Every failure in Fineract's document path — an unwritable
volume, a filename the whitelist rejects, a MIME type it rejects, a file whose
contents disagree with its declared type — comes back as the *same* bare HTTP
500 with no message code. They are indistinguishable from the client. This
document is the sequence that tells them apart.

Everything below was read out of our own fork (`MpofuSlim/fineract`) and out of
`deploy/fineract/docker-compose.yml` in this repo. File and line references are
included so you can check any claim.

---

## First, rule out the hypothesis that has been circulating

The failure has been reported as an S3 misconfiguration — *"`amazon-s3`
selected with empty credentials"*. **That cannot be the mechanism on this
cell.** Three independent reasons:

1. **The cell does not use S3.** `deploy/fineract/docker-compose.yml:172` sets
   `FINERACT_CONTENT_FILESYSTEM_ROOT_FOLDER: /fineract-content` and it is the
   only content-storage environment variable in the entire `deploy/` tree. The
   compose file says so itself at `:24` — *"filesystem storage is the active
   content backend (S3 is deliberately off)"*.
2. **`fineract.content.s3.enabled` defaults to `false`**
   (`application.properties:188`) and nothing overrides it, so the
   `S3ContentStoreService` bean — gated by
   `@ConditionalOnProperty("fineract.content.s3.enabled")` at
   `S3ContentStoreService.java:48` — is never created. There is no S3 client to
   misconfigure.
3. **The `amazon-s3` switch in the Fineract console is dead code.**
   `ConfigurationDomainServiceJpa.isAmazonS3Enabled()` (`:84-86`) has **zero
   callers** anywhere in the fork; the `ContentRepositoryFactory` that used to
   read it does not exist in this build. Toggling that row writes
   `enabled=true` to `c_configuration`, evicts a cache entry, and changes
   nothing about where bytes go.

Storage selection happens once, at boot, from Spring properties. It is not
reachable from the console at all.

> [!NOTE]
> Two S3 misconfigurations *would* be fatal, but neither produces a 500 — both
> stop the container from starting. An empty region makes
> `ContentS3Config.contentS3Client` throw `SdkClientException` from
> `builder.build()` (`ContentS3Config.java:47-55`), and enabling S3 without also
> setting `FINERACT_CONTENT_FILESYSTEM_ENABLED=false` leaves two
> `ContentStoreService` beans competing for a single by-type injection point →
> `NoUniqueBeanDefinitionException` at startup. If you are getting HTTP
> responses at all, neither of these is happening.

---

## Why every cause looks identical

This is the part that has cost the most time, and it is a defect in our fork.

Every failure inside `fineract-document` is wrapped in an
`AbstractPlatformException` subclass — `ContentStoreException`,
`ContentPolicyException`, `ContentDetectorException`. **There is no
`ExceptionMapper<AbstractPlatformException>` anywhere in the fork.** The 39
mapper classes cover `AbstractPlatformDomainRuleException`,
`AbstractPlatformResourceNotFoundException` and
`AbstractPlatformServiceUnavailableException` — never the base class these
three extend.

`DocumentWritePlatformServiceImpl:85-87` funnels everything through
`ErrorHandler.getMappable`, which returns any `RuntimeException` unchanged
(`ErrorHandler.java:190-192`), discarding the message code the exception was
carrying. Jersey then finds no registered mapper and emits a bare 500.

Compounding it, the only catch-all — `DefaultExceptionMapper` — is missing its
`@Provider` annotation (`:33-36`), while `JerseyConfig.setup()` registers only
`@Path`- and `@Provider`-annotated beans (`:58,60`). So it is never in Jersey's
registry, and the 500 body is container HTML rather than JSON. (It also
hardcodes `SC_INTERNAL_SERVER_ERROR` at `:47`, so registering it would fix the
*body* but never the *status*.)

**Consequence: a user error and an infrastructure failure are the same opaque
500.** A fix is described at the end of this document.

---

## Step 0 — is this cell running the volume fix?

On 2026-09-08, commit `4533de8` fixed a document-upload 500 on the ZW cell. The
Jib image runs Fineract as uid 65534 and `/fineract-content` does not exist
inside the image, so Docker initialised the fresh named volume `root:root 755`
and every upload died. The fix is a one-shot `alpine chown` service that the
`fineract` service hard-depends on (`docker-compose.yml:20-34`, gated at
`:76-77` with `service_completed_successfully`).

That commit was validated with `docker compose config` only. **It was never
confirmed with an actual upload.** So check first:

```bash
docker compose ps -a | grep content-init
```

Must show `Exited (0)`. Then:

```bash
docker compose exec fineract sh -c 'id; ls -ld /fineract-content'
```

Must show uid `65534` owning the directory. If either check fails, the cell is
running an older compose and the entire remaining action is:

```bash
docker compose up -d
```

That creates the init service, runs it, and recreates nothing else.

> [!IMPORTANT]
> The failing call is `Files.createDirectories` for a per-upload randomised
> subdirectory (`FileContentStoreService.java:158`), not just a file write — so
> a world-writable *file* tree still fails if uid 65534 cannot create
> directories in it. Check the directory, not a file.

---

## The probe sequence

Set up an SSH tunnel first — the cell publishes on loopback only
(`docker-compose.yml:193`):

```bash
ssh -L 8443:127.0.0.1:8443 <cell-host>
```

The context path is `/fineract-provider` (`application.properties:382`) and the
Jersey application path is `/api`, so:

```bash
export U="https://localhost:8443/fineract-provider/api/v1/loans/1/documents"
export B="-k -u <user>:<pass> -H Fineract-Platform-TenantId:default"
```

`-k` because the cell keystore is self-signed. Save a real, valid, under-5MB
PDF as `probe.pdf`.

### Probe G — rule out permissions (run first, cheapest)

```bash
curl -i $B "$U"
```

`POST /api/*/loans/*/documents` is guarded by `ALL_FUNCTIONS` /
`ALL_FUNCTIONS_WRITE` / `CREATE_DOCUMENT` in `SecurityConfig.java:234-235`,
enforced by Spring Security *before* Jersey. A user lacking the permission gets
**403, never 500** — so if your POST returns 403, that is the whole answer and
nothing else in this document applies.

### Probe A — the healthy baseline

```bash
curl -i $B -F 'file=@probe.pdf;type=application/pdf' -F 'name=probe' -F 'description=probe' "$U"
```

**Healthy** = HTTP 200, `Content-Type: application/json`, body
`{"resourceId":<n>,"resourceIdentifier":"loans"}`
(`DocumentWritePlatformServiceImpl.java:84-85`).

- **A fails** → the store itself is broken. Go back to Step 0, or see probe E.
- **A succeeds** → the store is fine, and what your operators are hitting is a
  *policy rejection* on their real files. Continue to B, C, D.

### Probe B — the filename whitelist

```bash
cp probe.pdf probe.PDF
curl -i $B -F 'file=@probe.PDF;type=application/pdf' -F 'name=probe' -F 'description=probe' "$U"
```

**Fails while A succeeds → this is your cause.** `WhitelistContentPolicy.java:44`
compiles the pattern with plain `Pattern.compile` — **no `CASE_INSENSITIVE`
flag** — and matches with `matches()` at `:52`, which requires a *full* match.

The literal default (`application.properties:182`) is:

```
.*\.pdf$,.*\.doc,.*\.docx,.*\.xls,.*\.xlsx,.*\.jpg,.*\.jpeg,.*\.png
```

So all of these are **rejected**, and several are what scanners and phones
actually produce:

| Rejected | Why it matters |
|---|---|
| `APPRAISAL.PDF`, `SCAN.JPG` | any uppercase extension — scanners and phone cameras routinely emit these |
| `.tif` / `.tiff` | the default output format of most office scanners |
| `.heic` | iPhone photos |
| `.odt`, `.rtf`, `.txt`, `.csv` | |
| `.gif`, `.bmp`, `.webp` | |
| `.zip`, `.eml`, `.msg` | |
| no extension at all | |

Log line: `File name not allowed: <name>` (`WhitelistContentPolicy.java:55`).

### Probe C — the MIME whitelist

```bash
curl -i $B -F 'file=@probe.pdf;type=application/octet-stream' -F 'name=probe' -F 'description=probe' "$U"
```

Same policy, `:59-69`. The declared type is whatever the client puts on the
file part — `DocumentApiResource.java:179-182` takes `filePart.getMediaType()`
first. The literal default (`application.properties:184`) is seven types:
`application/pdf`, `application/msword`, the two OOXML types,
`application/vnd.ms-excel`, `image/jpeg`, `image/png`. **`image/tiff` and
`image/heic` are absent**, consistent with probe B.

A client that sends `application/octet-stream` fails this check outright.

Log lines: `Detected mime type %s for filename %s not allowed!` (`:68`) or
`Could not detect mime type for filename %s!` (`:63`).

### Probe D — the Tika post-upload check

```bash
curl -i $B -F 'file=@probe.pdf;type=image/png' -F 'name=probe' -F 'description=probe' "$U"
```

`MimeContentPolicy.java:37-45` runs **unconditionally — there is no property
gate on it at all**. It re-reads the file from disk after writing and compares
Tika's sniffed type against the declared type.

Classic real-world hits: legacy `.doc`/`.xls` sniffing as
`application/x-tika-msoffice`, `.docx`/`.xlsx` sniffing as `application/zip`,
or a JPEG somebody renamed to `.pdf`.

**Distinctive signature:** the bytes are written and *then deleted*
(`FileContentStoreService.java:107-113`), so the file briefly appears on the
volume and vanishes. Log lines: `Delete file because it didn't comply with the
post-upload policy check: ...` (`:108`) and `Detected file type (%s), but mime
type (%s) was provided. Mismatch!` (`MimeContentPolicy.java:43-44`).

### Probe E — the multipart size limit

`application.properties:196-197`: `max-file-size` **5MB**, `max-request-size`
**10MB**, neither overridden in the cell compose. A scanned multi-page
appraisal form exceeds 5MB easily.

```bash
# a valid PDF padded past 5MB
curl -i $B -F 'file=@big.pdf;type=application/pdf' -F 'name=probe' -F 'description=probe' "$U"
```

Not this class of failure: a **missing `Content-Length`** (chunked upload).
`FileUploadValidator.java:35-38` requires it non-blank and `> 0` and throws
`PlatformApiDataValidationException`, which *is* mapped — that returns a clean
JSON **400**, not a 500.

### The cross-cutting tell

On any 500, look at the response `Content-Type`:

| Response | Meaning |
|---|---|
| `text/html` or empty | the exception reached the container unmapped — all of the causes above, today |
| `application/json` with an `"Exception"` key | `DefaultExceptionMapper` ran, which today means the **batch** path (`ErrorHandler.java:104/:121/:131`) |

---

## Confirming bytes actually landed

Do not trust a 200 alone — the post-upload policy deletes the file *after* a
successful write (`FileContentStoreService.java:110`).

```bash
docker compose exec fineract sh -c 'ls -ld /fineract-content; find /fineract-content -type f -newermt "-5 minutes" -ls'
```

Expected path shape:
`/fineract-content/<TenantName>/documents/loans/<loanId>/<random>/<fileName>` —
root and tenant from `FileContentStoreService.java:175-176`, the
`documents/entityType/entityId/fileName` join from
`DocumentWritePlatformServiceImpl.java:171`, the `<random>` segment from `:154`.
Use `find` rather than a hardcoded path: the tenant directory is derived from
the tenant's `name` column, not from a constant.

```bash
docker compose exec fineract-db psql -U fineract -d fineract_default \
  -c "select id,parent_entity_type,parent_entity_id,file_name,type,size,location,storage_type from m_document order by id desc limit 5;"
```

`location` must match the file found on disk. Then the only real proof —
round-trip the bytes:

```bash
curl $B -o out.pdf "$U/<resourceId>/attachment" && cmp out.pdf probe.pdf
```

A clean `cmp` is the only evidence the bytes survived both the write and the
post-upload check.

> [!NOTE]
> `DocumentApiResource` does **no** validation of `entityType` or `entityId` —
> a POST against a loan id that does not exist still succeeds. **A 500 is never
> "loan not found."** Worth knowing because this cell is currently savings-only
> (see the `FINERACT_JOB_LOAN_COB_ENABLED` note at `docker-compose.yml:164`).

---

## Fixes

### Immediate, no code, deployable today

If probes B or C are the cause, widen both whitelists in the `fineract`
service's `environment:` block in `deploy/fineract/docker-compose.yml`, next to
`FINERACT_CONTENT_FILESYSTEM_ROOT_FOLDER` at `:172`:

```yaml
FINERACT_CONTENT_REGEX_WHITELIST: "(?i).*\\.pdf$,(?i).*\\.docx?$,(?i).*\\.xlsx?$,(?i).*\\.jpe?g$,(?i).*\\.png$,(?i).*\\.tiff?$"
FINERACT_CONTENT_MIME_WHITELIST: "application/pdf,application/msword,application/vnd.openxmlformats-officedocument.wordprocessingml.document,application/vnd.ms-excel,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,image/jpeg,image/png,image/tiff"
```

The `(?i)` prefix kills the uppercase-extension class outright.

> [!WARNING]
> **Widen both lists in lockstep.** Adding a filename pattern without its
> matching MIME type just moves the failure from the pre-upload check (probe B)
> to the post-upload Tika check (probe D) — same 500, different line.
>
> Put these in the compose `environment:` block, **not in `.env`** — per the
> compose file's own comments at `:113-118`, `.env` only feeds `${...}`
> interpolation and is not passed into containers.

Which formats the institution accepts as an appraisal form is a policy call for
CBS, not an engineering one. `(?i)` is unambiguously right; `tif`/`tiff` and
`heic` need a decision.

### The fork fix (pending)

Three new `@Provider` mappers in `fineract-document`, beside the exceptions they
map:

| Mapper | Status | Rationale |
|---|---|---|
| `ContentPolicyExceptionMapper` | 400 | a rejected filename or MIME type is a client error |
| `ContentDetectorExceptionMapper` | 400 | fires on user-supplied bytes and filenames |
| `ContentStoreExceptionMapper` | 500 | an unwritable volume genuinely *is* a server fault — but it becomes a *labelled, greppable* 500 carrying `error.msg.content.store` instead of an anonymous one |

Plus `@Provider` on `DefaultExceptionMapper` (with a `WebApplicationException`
passthrough guard, so 404s and 405s are not swallowed).

After that change, probes B, C and D stop needing the container log at all:

```
.PDF filename      500 bare  →  400 {"userMessageGlobalisationCode":"error.msg.content.policy",
                                     "defaultUserMessage":"File name not allowed: APPRAISAL.PDF"}
octet-stream part  500 bare  →  400, "Detected mime type ... not allowed!"
Tika mismatch      500 bare  →  400, "Detected file type (...), but mime type (...) was provided."
unwritable volume  500 bare  →  500 JSON, code error.msg.content.store, naming the path
```

> [!IMPORTANT]
> This is the half of the issue that is **ours, not the ops team's**. No
> deployment configuration can make a rejected filename return a 4xx —
> `MimeContentPolicy` has no property gate at all, so a mislabeled file returns
> a bare 500 under every possible env var, volume mode and global-config row.

---

## Summary table

| Probe | Fails while A succeeds → | Fix |
|---|---|---|
| Step 0 | volume not chowned | `docker compose up -d` (picks up `4533de8`) |
| G → 403 | missing `CREATE_DOCUMENT` | grant the permission |
| A | store broken, or file > 5MB | Step 0, then probe E |
| B | uppercase / unlisted extension | `(?i)` in `FINERACT_CONTENT_REGEX_WHITELIST` |
| C | declared MIME not whitelisted | add to `FINERACT_CONTENT_MIME_WHITELIST` |
| D | contents disagree with declared type | fix the client's `Content-Type`, or re-save the file |
| E | over `max-file-size` | raise `FINERACT_MULTIPART_FILE_SIZE` |
