package zw.co.innbucks.middleware.me.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.middleware.me.AccountView;
import zw.co.innbucks.middleware.me.ProfileNotFoundException;
import zw.co.innbucks.middleware.me.ProfileResponse;
import zw.co.innbucks.middleware.me.ProfileService;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.time.LocalDate;
import zw.co.innbucks.middleware.me.StatementView;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.format.annotation.DateTimeFormat;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import io.swagger.v3.oas.annotations.Parameter;

@RestController
@RequestMapping("/me")
@Validated
@RequiredArgsConstructor
@Tag(name = "me", description = "Current-customer endpoints. Identity is taken from the Bearer JWT.")
public class MeController {

    /** Hard ceiling on page size: a long-lived wallet's full history is not a
     *  thing to pull into memory, and the port contract forbids an unbounded query. */
    private static final int MAX_PAGE_SIZE = 100;


    private final ProfileService profileService;

    @GetMapping("/profile")
    @Operation(summary = "Get the authenticated customer's profile",
            description = "Local identity (status, KYC tier, MSISDN) merged with the core banking "
                    + "system's client record (names).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Profile returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid Bearer token",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No registered customer behind this token",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "Core banking system unreachable",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PreAuthorize("hasAuthority('SCOPE_customer:read')")
    public ProfileResponse profile(@AuthenticationPrincipal Jwt jwt) {
        return profileService.profileFor(UUID.fromString(jwt.getSubject()));
    }

    @GetMapping("/accounts")
    @Operation(summary = "List the authenticated customer's accounts",
            description = "Wallet/deposit accounts with available balances in minor units. The "
                    + "accountId values are what /transactions/* endpoints accept.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Accounts returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid Bearer token",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No registered customer behind this token",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "Core banking system unreachable",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PreAuthorize("hasAuthority('SCOPE_customer:read')")
    public List<AccountView> accounts(@AuthenticationPrincipal Jwt jwt) {
        return profileService.accountsFor(UUID.fromString(jwt.getSubject()));
    }

    @GetMapping("/accounts/{accountId}/transactions")
    @Operation(summary = "Statement for one of the caller's accounts",
            description = "Entries newest first, as recorded by the core banking system — so it "
                    + "includes activity that did not originate here (interest postings, fees, "
                    + "teller corrections) and reconciles against the balance on /me/accounts. "
                    + "Amounts are in MINOR units and always positive; `direction` carries the sign. "
                    + "A movement still being reconciled has no confirmed core entry yet and will "
                    + "not appear until it settles.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Statement page returned"),
            @ApiResponse(responseCode = "400", description = "Bad paging or date range (to before from)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid Bearer token",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Account does not belong to the caller",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No registered customer behind this token",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "Core banking system unreachable",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PreAuthorize("hasAuthority('SCOPE_customer:read')")
    public StatementView transactions(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "From /me/accounts.", example = "3f0d1c2e-…:wallet")
            @PathVariable String accountId,
            @Parameter(description = "Inclusive value date, ISO yyyy-MM-dd. Omit for open-ended.")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Inclusive value date, ISO yyyy-MM-dd. Omit for open-ended.")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "Entries to skip.", example = "0")
            @RequestParam(defaultValue = "0") @Min(0) int offset,
            @Parameter(description = "Page size, 1-" + MAX_PAGE_SIZE + ".", example = "20")
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int limit) {
        return profileService.statementFor(
                UUID.fromString(jwt.getSubject()), accountId, from, to, offset, limit);
    }

    @ExceptionHandler(ProfileNotFoundException.class)
    public ResponseEntity<ProblemDetail> notFound(ProfileNotFoundException ex) {
        ProblemDetail body = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        body.setType(URI.create("about:blank"));
        body.setTitle("Not registered");
        body.setDetail("We couldn't find a registered account for this login. Please register first.");
        body.setProperty("errorCode", "customer_not_registered");
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }
}
