package zw.co.innbucks.middleware.kyc.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.middleware.idempotency.IdempotencyService;
import zw.co.innbucks.middleware.kyc.ConditionalOnKycEnabled;
import zw.co.innbucks.middleware.kyc.CreateKycSubmissionRequest;
import zw.co.innbucks.middleware.kyc.KycFileStoredView;
import zw.co.innbucks.middleware.kyc.KycRequestException;
import zw.co.innbucks.middleware.kyc.KycService;
import zw.co.innbucks.middleware.kyc.KycStatusView;
import zw.co.innbucks.middleware.kyc.KycSubmissionCreated;
import zw.co.innbucks.middleware.kyc.KycSubmitAccepted;
import zw.co.innbucks.middleware.ratelimit.ClientIpResolver;

import java.io.IOException;
import java.util.UUID;

/**
 * KYC intake for the app. Exists only with {@code innbucks.kyc.enabled=true};
 * otherwise these paths are unmapped and answer the ordinary 404
 * {@code not_found}. Every response is {@code Cache-Control: no-store}.
 */
@RestController
@RequestMapping("/me/kyc")
@ConditionalOnKycEnabled
@Tag(name = "kyc", description = "Apply for a higher KYC tier: open a request, upload its photos, submit. "
        + "Staff decide; nothing here changes the tier. Identity is taken from the Bearer JWT.")
public class KycController {

    static final String STATUS_REJECTED_EXAMPLE = """
            {"kycTier":"basic",
             "nextTier":{"tier":"standard","needs":["national_id","id_front","id_back","selfie"]},
             "onFile":{"nationalId":null,"idFront":false,"idBack":false,"selfie":false,
                       "proofOfAddress":false,"address":false},
             "submission":{"id":"b1c2d3e4-5f60-4a7b-8c9d-0e1f2a3b4c5d","targetTier":"standard",
                           "nationalId":"63•••••••X18","status":"rejected","round":2,
                           "submittedAt":"2026-10-28T09:14:00+02:00","decidedAt":"2026-10-28T15:02:00+02:00",
                           "rejection":{"reasons":["id_photo_unreadable"],"redo":["id_front"]},
                           "missing":["id_front"],"expiresAt":"2026-11-11T15:02:00+02:00"}}
            """;

    static final String STATUS_DRAFT_EXAMPLE = """
            {"kycTier":"basic",
             "nextTier":{"tier":"standard","needs":["national_id","id_front","id_back","selfie"]},
             "onFile":{"nationalId":null,"idFront":false,"idBack":false,"selfie":false,
                       "proofOfAddress":false,"address":false},
             "submission":{"id":"b1c2d3e4-5f60-4a7b-8c9d-0e1f2a3b4c5d","targetTier":"standard",
                           "nationalId":"63•••••••X18","status":"draft","round":1,"submittedAt":null,
                           "decidedAt":null,"rejection":null,
                           "missing":["id_back","selfie"],"expiresAt":"2026-11-11T08:40:00+02:00"}}
            """;

    static final String STATUS_UPGRADING_EXAMPLE = """
            {"kycTier":"standard",
             "nextTier":{"tier":"enhanced",
                         "needs":["national_id","id_front","id_back","selfie","address","proof_of_address"]},
             "onFile":{"nationalId":"63•••••••X18","idFront":true,"idBack":true,"selfie":true,
                       "proofOfAddress":false,"address":false},
             "submission":{"id":"c2d3e4f5-6071-4b8c-9d0e-1f2a3b4c5d6e","targetTier":"enhanced",
                           "nationalId":"63•••••••X18","status":"pending_review","round":1,
                           "submittedAt":"2026-12-02T10:05:00+02:00","decidedAt":null,"rejection":null,
                           "missing":null,"expiresAt":null}}
            """;

    static final String CREATED_EXAMPLE = """
            {"id":"b1c2d3e4-5f60-4a7b-8c9d-0e1f2a3b4c5d","round":1,"needs":["id_front","id_back","selfie"]}
            """;

    static final String SUBMISSION_OPEN_EXAMPLE = """
            {"type":"about:blank","title":"Request already open","status":409,
             "detail":"You already have a verification request in progress. Continue that one.",
             "errorCode":"kyc_submission_open","submissionId":"b1c2d3e4-5f60-4a7b-8c9d-0e1f2a3b4c5d"}
            """;

    static final String NOT_AVAILABLE_EXAMPLE = """
            {"type":"about:blank","title":"Nothing to upgrade","status":409,
             "detail":"Your account is already on the highest level.","errorCode":"kyc_not_available"}
            """;

    static final String NATIONAL_ID_INVALID_EXAMPLE = """
            {"type":"about:blank","title":"Check the ID number","status":400,
             "detail":"That ID number doesn't look right: a Zimbabwean national ID is 2 digits, 6 or 7 digits, a letter and 2 digits, e.g. 63-1234567 X 18.",
             "errorCode":"national_id_invalid"}
            """;

    static final String TARGET_INVALID_EXAMPLE = """
            {"type":"about:blank","title":"Check the level","status":400,
             "detail":"targetTier must be standard or enhanced.","errorCode":"kyc_target_invalid"}
            """;

    static final String ADDRESS_INVALID_EXAMPLE = """
            {"type":"about:blank","title":"Check the address","status":400,
             "detail":"Enter your residential address.","errorCode":"kyc_address_invalid"}
            """;

    static final String STORED_EXAMPLE = """
            {"kind":"id_front","round":1,"width":1600,"height":1010,"byteSize":287412,"replaced":false,
             "missing":["id_back","selfie"]}
            """;

    static final String FILE_INVALID_EXAMPLE = """
            {"type":"about:blank","title":"Check the photo","status":400,
             "detail":"That doesn't look like a camera photo we can use. Retake it and try again.",
             "errorCode":"kyc_file_invalid","reason":"not_jpeg"}
            """;

    static final String FILE_NOT_NEEDED_EXAMPLE = """
            {"type":"about:blank","title":"File not needed","status":400,
             "detail":"This request doesn't need that file now.","errorCode":"kyc_file_not_needed",
             "kind":"proof_of_address","uploadable":["id_front","id_back","selfie"]}
            """;

    static final String NOT_FOUND_EXAMPLE = """
            {"type":"about:blank","title":"Not found","status":404,
             "detail":"We couldn't find that verification request.","errorCode":"kyc_submission_not_found"}
            """;

    static final String NOT_OPEN_EXAMPLE = """
            {"type":"about:blank","title":"Request closed","status":409,
             "detail":"This verification request can't be changed any more.",
             "errorCode":"kyc_submission_not_open","submissionStatus":"pending_review"}
            """;

    static final String TOO_LARGE_EXAMPLE = """
            {"type":"about:blank","title":"File too large","status":413,
             "detail":"That photo is too large. Retake it or send it at a lower size.",
             "errorCode":"kyc_file_too_large","maxBytes":2097152}
            """;

    static final String BUSY_EXAMPLE = """
            {"type":"about:blank","title":"Busy","status":503,
             "detail":"We're processing a lot of photos right now. Please send it again in a moment.",
             "errorCode":"kyc_upload_busy","retryAfterSeconds":5}
            """;

    static final String ACCEPTED_EXAMPLE = """
            {"status":"pending_review","round":1}
            """;

    static final String INCOMPLETE_EXAMPLE = """
            {"type":"about:blank","title":"Something is missing","status":422,
             "detail":"Add the missing files, then submit again.","errorCode":"kyc_incomplete",
             "missing":["selfie"]}
            """;

    static final String RATE_LIMITED_EXAMPLE = """
            {"type":"about:blank","title":"Too many requests","status":429,
             "detail":"You've made too many requests in a short time. Please wait a moment and try again.",
             "errorCode":"rate_limited","retryAfterSeconds":40}
            """;

    static final String TIER_UNAVAILABLE_EXAMPLE = """
            {"type":"about:blank","title":"Service temporarily unavailable","status":503,
             "detail":"We couldn't confirm your account level just now. Please try again shortly.",
             "errorCode":"kyc_tier_unavailable"}
            """;

    private final KycService kycService;
    private final ClientIpResolver clientIpResolver;

    public KycController(KycService kycService, ClientIpResolver clientIpResolver) {
        this.kycService = kycService;
        this.clientIpResolver = clientIpResolver;
    }

    @GetMapping
    @Operation(summary = "The caller's KYC tier, what the next tier needs, and the current request",
            description = "kycTier is the tier payments are judged by now. nextTier is null at enhanced. "
                    + "onFile is the evidence of RECORD — what the latest approved request holds (masked ID "
                    + "only) — and stays all false until one is approved. submission is the open request, else "
                    + "the latest, with the masked ID it was made with; when its `missing` is not null the "
                    + "customer can still upload (`missing` lists what is left) until `expiresAt`. Resume an "
                    + "open request from here. Timestamps are at the market offset; show them as they are.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Status returned",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = KycStatusView.class),
                            examples = {
                                    @ExampleObject(name = "Rejected, reopened for one retake",
                                            value = STATUS_REJECTED_EXAMPLE),
                                    @ExampleObject(name = "Draft in progress", value = STATUS_DRAFT_EXAMPLE),
                                    @ExampleObject(name = "Standard on record, enhanced with staff",
                                            value = STATUS_UPGRADING_EXAMPLE)})),
            @ApiResponse(responseCode = "401", description = "Missing or invalid Bearer token",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No registered customer behind this token "
                    + "(customer_not_registered) — or KYC is switched off on this cell (not_found)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "The caller's tier could not be read; retry",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(value = TIER_UNAVAILABLE_EXAMPLE)))
    })
    @PreAuthorize("hasAuthority('SCOPE_customer:read')")
    public ResponseEntity<KycStatusView> status(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(kycService.status(UUID.fromString(jwt.getSubject())));
    }

    @PostMapping("/submissions")
    @Operation(summary = "Open a request for a higher tier",
            description = "Requires an Idempotency-Key: a retry with the same key and body replays the 201. "
                    + "targetTier must be above the caller's tier. The national ID is checked for shape "
                    + "(spaces, dashes and case ignored) and stored encrypted. An address is required for "
                    + "enhanced and refused otherwise. Only one request may be open: a second one is 409 "
                    + "kyc_submission_open with the open request's id. Resume from GET /me/kyc (its "
                    + "`submission`), never by calling this to find the open one: every call here, replays and "
                    + "refusals included, spends the customer's small daily budget for new requests (429 "
                    + "rate_limited once it is gone). An ID already used by another account is NOT refused "
                    + "here — staff see it.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Request opened (or replayed)",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = KycSubmissionCreated.class),
                            examples = @ExampleObject(value = CREATED_EXAMPLE))),
            @ApiResponse(responseCode = "400", description = "national_id_invalid, kyc_target_invalid, "
                    + "kyc_address_invalid, or the Idempotency-Key header is missing (missing_header)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class), examples = {
                            @ExampleObject(name = "ID number", value = NATIONAL_ID_INVALID_EXAMPLE),
                            @ExampleObject(name = "Target tier", value = TARGET_INVALID_EXAMPLE),
                            @ExampleObject(name = "Address", value = ADDRESS_INVALID_EXAMPLE)})),
            @ApiResponse(responseCode = "401", description = "Missing or invalid Bearer token",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No registered customer behind this token",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "kyc_submission_open (resume submissionId), "
                    + "kyc_not_available (already enhanced), or an identical request still in progress "
                    + "(idempotency_in_progress)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class), examples = {
                            @ExampleObject(name = "Already open", value = SUBMISSION_OPEN_EXAMPLE),
                            @ExampleObject(name = "Top tier", value = NOT_AVAILABLE_EXAMPLE)})),
            @ApiResponse(responseCode = "422", description = "Idempotency-Key reused with a different body "
                    + "(idempotency_conflict)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "429", description = "Too many requests from this customer or "
                    + "address (rate_limited); retry after Retry-After seconds",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(value = RATE_LIMITED_EXAMPLE))),
            @ApiResponse(responseCode = "503", description = "The caller's tier could not be read; retry",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(value = TIER_UNAVAILABLE_EXAMPLE)))
    })
    @PreAuthorize("hasAuthority('SCOPE_customer:write')")
    public ResponseEntity<KycSubmissionCreated> create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody CreateKycSubmissionRequest request,
            HttpServletRequest httpRequest) {
        IdempotencyService.Result<KycSubmissionCreated> result = kycService.create(
                UUID.fromString(jwt.getSubject()), clientIpResolver.resolve(httpRequest), idempotencyKey, request);
        return ResponseEntity.status(result.status()).cacheControl(CacheControl.noStore()).body(result.body());
    }

    @RequestMapping(path = "/submissions/{submissionId}/files/{kind}",
            method = {RequestMethod.POST, RequestMethod.PUT})
    @Operation(summary = "Upload one photo into the request's open round (POST or PUT)",
            description = "The body is the raw JPEG bytes (Content-Type image/jpeg) — not a multipart form. "
                    + "Prefer POST: the Cloudflare WAF in front of the InnBucks domains refused PUT with a "
                    + "multipart body (measured on another service), and a raw-JPEG PUT through this edge is "
                    + "unmeasured. Sending "
                    + "a kind again in the same round replaces it. "
                    + "After a rejection only the kinds in rejection.redo can be sent. The photo is checked by "
                    + "its bytes (JPEG, baseline, not truncated, within the size and pixel caps), scaled down "
                    + "(1600 px documents, 1080 px selfie) and re-encoded: no EXIF or anything else survives. "
                    + "Bake orientation into the pixels — EXIF rotation is dropped, not applied. Treat ANY 413 "
                    + "as too large, whatever its body: the edge may answer it before we do.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, description = "The JPEG, at most 2 MiB",
            content = @Content(mediaType = MediaType.IMAGE_JPEG_VALUE,
                    schema = @Schema(type = "string", format = "binary")))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Stored",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = KycFileStoredView.class),
                            examples = @ExampleObject(value = STORED_EXAMPLE))),
            @ApiResponse(responseCode = "400", description = "kyc_file_invalid (reason: not_jpeg, corrupt, "
                    + "unsupported_encoding, too_many_pixels, too_small), kyc_file_kind_invalid (not one of "
                    + "the four kinds) or kyc_file_not_needed (the request does not take that kind now)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class), examples = {
                            @ExampleObject(name = "Not a usable photo", value = FILE_INVALID_EXAMPLE),
                            @ExampleObject(name = "Kind not needed", value = FILE_NOT_NEEDED_EXAMPLE)})),
            @ApiResponse(responseCode = "401", description = "Missing or invalid Bearer token",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such request for this customer — another "
                    + "customer's id answers exactly the same",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(value = NOT_FOUND_EXAMPLE))),
            @ApiResponse(responseCode = "409", description = "The request is submitted, decided or expired "
                    + "(kyc_submission_not_open, with submissionStatus)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(value = NOT_OPEN_EXAMPLE))),
            @ApiResponse(responseCode = "413", description = "Over 2 MiB (kyc_file_too_large). The edge may "
                    + "answer an HTML 413 for bodies far over: treat any 413 as too large",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(value = TOO_LARGE_EXAMPLE))),
            @ApiResponse(responseCode = "429", description = "Too many uploads from this customer or address "
                    + "(rate_limited); retry after Retry-After seconds",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(value = RATE_LIMITED_EXAMPLE))),
            @ApiResponse(responseCode = "503", description = "Too many photos are being received or "
                    + "processed right now (kyc_upload_busy); send it again after Retry-After",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(value = BUSY_EXAMPLE)))
    })
    @PreAuthorize("hasAuthority('SCOPE_customer:write')")
    public ResponseEntity<KycFileStoredView> upload(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "From POST /me/kyc/submissions.",
                    example = "b1c2d3e4-5f60-4a7b-8c9d-0e1f2a3b4c5d")
            @PathVariable String submissionId,
            @Parameter(description = "id_front, id_back, selfie or proof_of_address.", example = "id_front")
            @PathVariable String kind,
            HttpServletRequest httpRequest) throws IOException {
        KycFileStoredView stored = kycService.upload(UUID.fromString(jwt.getSubject()),
                clientIpResolver.resolve(httpRequest), submissionId(submissionId), kind,
                httpRequest.getContentLengthLong(), httpRequest.getInputStream());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(stored);
    }

    @PostMapping("/submissions/{submissionId}/submit")
    @Operation(summary = "Send the request's open round to staff",
            description = "Freezes the round: its ID and photos can no longer change, so staff decide exactly "
                    + "what was sent. Submitting a request that is already pending answers the same 202.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "With staff",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = KycSubmitAccepted.class),
                            examples = @ExampleObject(value = ACCEPTED_EXAMPLE))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid Bearer token",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such request for this customer",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(value = NOT_FOUND_EXAMPLE))),
            @ApiResponse(responseCode = "409", description = "Decided or expired (kyc_submission_not_open)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(value = NOT_OPEN_EXAMPLE))),
            @ApiResponse(responseCode = "422", description = "Files still missing (kyc_incomplete, `missing`)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(value = INCOMPLETE_EXAMPLE))),
            @ApiResponse(responseCode = "429", description = "Too many submits (rate_limited)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(value = RATE_LIMITED_EXAMPLE)))
    })
    @PreAuthorize("hasAuthority('SCOPE_customer:write')")
    public ResponseEntity<KycSubmitAccepted> submit(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(example = "b1c2d3e4-5f60-4a7b-8c9d-0e1f2a3b4c5d") @PathVariable String submissionId,
            HttpServletRequest httpRequest) {
        KycSubmitAccepted accepted = kycService.submit(UUID.fromString(jwt.getSubject()),
                clientIpResolver.resolve(httpRequest), submissionId(submissionId));
        return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore()).body(accepted);
    }

    /** A malformed id is the same 404 as an unknown one (and never a 500 from a type mismatch). */
    private static UUID submissionId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            throw KycRequestException.submissionNotFound();
        }
    }
}
