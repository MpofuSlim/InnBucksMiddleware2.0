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

@RestController
@RequestMapping("/me")
@RequiredArgsConstructor
@Tag(name = "me", description = "Current-customer endpoints. Identity is taken from the Bearer JWT.")
public class MeController {

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
