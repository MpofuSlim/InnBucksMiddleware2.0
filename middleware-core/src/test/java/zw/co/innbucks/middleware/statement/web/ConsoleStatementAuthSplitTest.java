package zw.co.innbucks.middleware.statement.web;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import zw.co.innbucks.middleware.common.country.Country;
import zw.co.innbucks.middleware.common.country.CountryProperties;
import zw.co.innbucks.middleware.config.CoreBankingExceptionHandler;
import zw.co.innbucks.middleware.config.GlobalExceptionHandler;
import zw.co.innbucks.middleware.corebanking.CoreBankingPort;
import zw.co.innbucks.middleware.corebanking.CoreOperatorPort;
import zw.co.innbucks.middleware.corebanking.CoreProvider;
import zw.co.innbucks.middleware.corebanking.exception.CoreAuthException;
import zw.co.innbucks.middleware.corebanking.exception.CoreOperatorAuthException;
import zw.co.innbucks.middleware.corebanking.value.DepositAccountKind;
import zw.co.innbucks.middleware.corebanking.value.OperatorAccountView;
import zw.co.innbucks.middleware.customer.CustomerNameResolver;
import zw.co.innbucks.middleware.customer.CustomerRepository;
import zw.co.innbucks.middleware.ratelimit.ClientIpResolver;
import zw.co.innbucks.middleware.statement.StatementProperties;
import zw.co.innbucks.middleware.statement.StatementService;
import zw.co.innbucks.middleware.statement.loan.LoanStatementService;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Whose credential the core refused, over HTTP, without Spring or Docker: the
 * real {@link ConsoleStatementController}, the real {@link StatementService}
 * and the real exception advice, with the two core ports mocked.
 *
 * <p>A deposit statement makes two kinds of call: it authorises AS THE
 * OPERATOR, then reads the transactions with this middleware's own read-only
 * service account. A refusal of the first is the operator's (401
 * operator_unauthorized). A refusal of the second is ours — a permission
 * missing from the read role, a wrong password in the cell's .env — and used
 * to come back as the same 401, telling the operator to fix a credential that
 * worked. ConsoleStatementFlowIntegrationTest drives the same split through
 * the real security chain.
 */
class ConsoleStatementAuthSplitTest {

    private static final String OPERATOR_BASIC = "Basic b3BlcmF0b3I6dGVsbGVyLXB3";

    private final CoreBankingPort corePort = mock(CoreBankingPort.class);
    private final CoreOperatorPort operatorPort = mock(CoreOperatorPort.class);

    private MockMvc mvc;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<CoreOperatorPort> operatorProvider = mock(ObjectProvider.class);
        when(operatorProvider.getIfAvailable()).thenReturn(operatorPort);
        StatementProperties properties = new StatementProperties(92, 1000, 100, 30);
        StatementService statementService = new StatementService(mock(CustomerRepository.class), corePort,
                mock(CustomerNameResolver.class), new CountryProperties(Country.ZW), properties,
                operatorProvider, new SimpleMeterRegistry());
        ClientIpResolver ips = mock(ClientIpResolver.class);
        when(ips.resolve(any())).thenReturn("198.51.100.7");

        mvc = MockMvcBuilders.standaloneSetup(new ConsoleStatementController(statementService,
                        mock(LoanStatementService.class), new ConsoleStatementRateLimiter(properties), ips))
                .setControllerAdvice(new CoreBankingExceptionHandler(), new GlobalExceptionHandler())
                .build();
    }

    @Test
    void aRejectedOperatorCredentialIs401AndTheMiddlewaresAccountIsNeverUsed() throws Exception {
        when(operatorPort.authorizeAndDescribeAccount(any(), eq("17")))
                .thenThrow(new CoreOperatorAuthException(CoreProvider.FINERACT,
                        "Fineract rejected the operator credential", null));

        mvc.perform(statement())
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("operator_unauthorized"));

        verifyNoInteractions(corePort);
    }

    @Test
    void ourOwnReadAccountRefusedIs502AndNeverBlamesTheOperator() throws Exception {
        when(operatorPort.authorizeAndDescribeAccount(any(), eq("17")))
                .thenReturn(new OperatorAccountView(null, DepositAccountKind.SAVINGS, "USD",
                        "000000017", "Shumba Traders", "0771234567"));
        when(corePort.listTransactions(any()))
                .thenThrow(new CoreAuthException(CoreProvider.FINERACT,
                        "Fineract refused the middleware AppUser a permission: "
                                + "error.msg.not.authorized: User has no authority to READ savingsaccounts", null));

        mvc.perform(statement())
                .andExpect(status().isBadGateway())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("core_banking_unavailable"))
                .andExpect(jsonPath("$.detail").value(containsString("Your credential was accepted")))
                // Fineract's wording stays in the log.
                .andExpect(jsonPath("$.detail").value(not(containsString("authority"))));

        // The operator WAS authorised first; the refusal came after, from our read.
        verify(operatorPort).authorizeAndDescribeAccount(any(), eq("17"));
    }

    private static org.springframework.test.web.servlet.RequestBuilder statement() {
        return get("/console/savings-accounts/{id}/statement", "17")
                .param("from", "2026-08-01").param("to", "2026-08-31")
                .header(HttpHeaders.AUTHORIZATION, OPERATOR_BASIC);
    }
}
