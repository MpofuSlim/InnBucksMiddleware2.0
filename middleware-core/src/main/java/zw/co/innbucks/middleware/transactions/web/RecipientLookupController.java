package zw.co.innbucks.middleware.transactions.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.middleware.common.msisdn.InvalidMsisdnException;
import zw.co.innbucks.middleware.transactions.RecipientLookupService;
import zw.co.innbucks.middleware.transactions.RecipientNotFoundException;
import zw.co.innbucks.middleware.transactions.RecipientView;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/accounts")
@RequiredArgsConstructor
@Tag(name = "accounts", description = "Account resolution for the transfer flow.")
public class RecipientLookupController {

    private final RecipientLookupService lookupService;

    /**
     * POST, not GET, on purpose: an MSISDN in a query string lands verbatim in
     * every access log between the phone and this app (nginx, Cloudflare).
     * Keeping it in the body keeps PII out of infrastructure logs.
     */
    @PostMapping("/lookup")
    @PreAuthorize("hasAuthority('SCOPE_customer:read')")
    @Operation(summary = "Resolve a phone number to a transfer recipient",
            description = "For the P2P flow: the sender types a phone number, the app shows the masked "
                    + "name this returns ('Send to Tariro M.?'), and on confirmation passes accountId as "
                    + "toAccountId on POST /transactions/transfer. Accepts local (07...) and international "
                    + "(+263.../263...) forms; the response echoes the normalised E.164 number. "
                    + "Rate-limited per customer; a 404 means the number cannot receive money right now "
                    + "— it deliberately does not distinguish why.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Recipient resolved",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = RecipientView.class),
                            examples = @ExampleObject(value = """
                                    {"accountId":"9a8b7c6d-5e4f-4a3b-2c1d-0e9f8a7b6c5d:wallet",
                                     "displayName":"Tariro M.",
                                     "msisdn":"+263771234567"}
                                    """))),
            @ApiResponse(responseCode = "400", description = "The number's shape is invalid for this market",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(value = """
                                    {"status":400,"title":"Check the phone number",
                                     "detail":"That phone number doesn't look right. Please enter it in the format used in your country and try again.",
                                     "errorCode":"invalid_msisdn"}
                                    """))),
            @ApiResponse(responseCode = "404", description = "The number cannot receive money right now",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(value = """
                                    {"status":404,"title":"Recipient not found",
                                     "detail":"That number isn't registered to receive money. Check the number, or invite them to join InnBucks.",
                                     "errorCode":"recipient_not_found"}
                                    """))),
            @ApiResponse(responseCode = "429", description = "Lookup budget exhausted; retry after Retry-After seconds",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public RecipientView lookup(@AuthenticationPrincipal Jwt jwt,
                                @Valid @RequestBody LookupRequest request) {
        return lookupService.lookup(UUID.fromString(jwt.getSubject()), request.msisdn());
    }

    /**
     * Controller-local so a malformed number gets THIS endpoint's 400 —
     * without it, {@code InvalidMsisdnException} falls to
     * {@code AuthExceptionHandler}'s login copy ("phone number or PIN"),
     * the exact wrong-screen bug the FE already reported once on register/OTP.
     */
    @ExceptionHandler(InvalidMsisdnException.class)
    public ResponseEntity<ProblemDetail> invalidMsisdn(InvalidMsisdnException ex) {
        ProblemDetail body = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        body.setType(URI.create("about:blank"));
        body.setTitle("Check the phone number");
        body.setDetail("That phone number doesn't look right. "
                + "Please enter it in the format used in your country and try again.");
        body.setProperty("errorCode", "invalid_msisdn");
        return ResponseEntity.badRequest()
                .contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }

    @ExceptionHandler(RecipientNotFoundException.class)
    public ResponseEntity<ProblemDetail> recipientNotFound(RecipientNotFoundException ex) {
        ProblemDetail body = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        body.setType(java.net.URI.create("about:blank"));
        body.setTitle("Recipient not found");
        body.setDetail("That number isn't registered to receive money. "
                + "Check the number, or invite them to join InnBucks.");
        body.setProperty("errorCode", "recipient_not_found");
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }

    @Schema(name = "RecipientLookupRequest")
    public record LookupRequest(
            @NotBlank
            @Schema(description = "The recipient's mobile number, any local or international form.",
                    example = "0771234567")
            String msisdn
    ) {
    }
}
