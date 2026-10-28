package zw.co.innbucks.middleware.transactions.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import zw.co.innbucks.middleware.config.CoreBankingExceptionHandler;
import zw.co.innbucks.middleware.corebanking.CoreProvider;
import zw.co.innbucks.middleware.corebanking.exception.CoreAccountRestrictedException;
import zw.co.innbucks.middleware.corebanking.exception.CoreClientException;
import zw.co.innbucks.middleware.transactions.MovementService;

import java.util.UUID;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP answer to a core restriction, through the real controller and the
 * real advice: 403 {@code account_restricted} with fixed copy, never the
 * core's wording — and the generic refusal (422 {@code core_rejected}) is
 * untouched, so the more specific handler is what makes the difference.
 * Standalone MockMvc (no Spring context, no Docker); the full stack with the
 * ledger and the same-key replay is {@code TransactionFlowIntegrationTest}.
 */
class AccountRestrictedResponseTest {

    private static final String DETAIL = "Payments from this account are paused. Please contact support.";

    private final MovementService movementService = mock(MovementService.class);
    private final UUID customerId = UUID.randomUUID();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TransactionController(movementService))
                .setControllerAdvice(new CoreBankingExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "HS256").subject(customerId.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aBlockedWithdrawalIs403AccountRestrictedWithFixedCopy() throws Exception {
        when(movementService.withdraw(eq(customerId), anyString(), any(), any()))
                .thenThrow(new CoreAccountRestrictedException(CoreProvider.FINERACT,
                        "Fineract refused to debit a restricted account "
                                + "(error.msg.savings.account.debit.transaction.not.allowed)", null));

        mockMvc.perform(post("/transactions/withdraw")
                        .header("Idempotency-Key", "wd-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":"acct-1:wallet","amountMinor":500,"currency":"USD"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("account_restricted"))
                .andExpect(jsonPath("$.title").value("Account restricted"))
                .andExpect(jsonPath("$.detail").value(DETAIL))
                // Nothing of the core's reason reaches the customer.
                .andExpect(content().string(not(containsString("error.msg"))));
    }

    @Test
    void aBlockedTransferSourceIs403AccountRestrictedToo() throws Exception {
        when(movementService.transfer(eq(customerId), anyString(), any(), any()))
                .thenThrow(new CoreAccountRestrictedException(CoreProvider.FINERACT,
                        "Fineract refused to debit a restricted account "
                                + "(error.msg.saving.account.blocked.transaction.not.allowed)", null));

        mockMvc.perform(post("/transactions/transfer")
                        .header("Idempotency-Key", "tr-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromAccountId":"acct-1:wallet","toAccountId":"acct-2:wallet",
                                 "amountMinor":500,"currency":"USD"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("account_restricted"))
                .andExpect(jsonPath("$.detail").value(DETAIL));
    }

    @Test
    void aBlockedRecipientStaysTheGeneric422() throws Exception {
        when(movementService.transfer(eq(customerId), anyString(), any(), any()))
                .thenThrow(new CoreClientException(CoreProvider.FINERACT,
                        "Fineract vetoed the request: error.msg.savings.account.credit.transaction.not.allowed: "
                                + "the receiving account cannot accept this payment", null));

        mockMvc.perform(post("/transactions/transfer")
                        .header("Idempotency-Key", "tr-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromAccountId":"acct-1:wallet","toAccountId":"acct-2:wallet",
                                 "amountMinor":500,"currency":"USD"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("core_rejected"))
                .andExpect(jsonPath("$.detail").value(containsString("the receiving account cannot accept")));
    }
}
