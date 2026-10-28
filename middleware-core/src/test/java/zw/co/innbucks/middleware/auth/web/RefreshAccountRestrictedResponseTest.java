package zw.co.innbucks.middleware.auth.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import zw.co.innbucks.middleware.audit.AuditService;
import zw.co.innbucks.middleware.auth.config.AuthProperties;
import zw.co.innbucks.middleware.auth.exception.AccountRestrictedSessionException;
import zw.co.innbucks.middleware.auth.exception.RefreshTokenInvalidException;
import zw.co.innbucks.middleware.auth.jwt.JwtIssuer;
import zw.co.innbucks.middleware.auth.login.LoginService;
import zw.co.innbucks.middleware.auth.refresh.AccountBlockSessionGuard;
import zw.co.innbucks.middleware.auth.refresh.RefreshToken;
import zw.co.innbucks.middleware.auth.refresh.RefreshTokenService;
import zw.co.innbucks.middleware.customer.Customer;
import zw.co.innbucks.middleware.customer.CustomerRepository;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /auth/refresh} and a bank block, through the real controller and
 * the real advice (standalone MockMvc, no Spring context, no Docker): the
 * block check runs only once the token has been READ as live and BEFORE
 * rotation spends it — so a full block, or a failure in the check's own path,
 * rotates nothing (it does NOT save a client that gives up waiting; the
 * guard's timeout does that) — a full block is 403 {@code account_restricted}
 * with fixed copy and mints no token, and a token that is not live never
 * reaches the check (rotation refuses it, as it always did). The Postgres half
 * is {@code RefreshAccountBlockIntegrationTest}.
 */
class RefreshAccountRestrictedResponseTest {

    private static final String SECRET = "6f1c2d3e-4a5b-4c6d-8e7f-9a0b1c2d3e4f";
    private static final String BODY = """
            {"refreshToken":"%s","deviceHash":"device-1"}
            """.formatted(SECRET);

    private final RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);
    private final CustomerRepository customerRepository = mock(CustomerRepository.class);
    private final JwtIssuer jwtIssuer = mock(JwtIssuer.class);
    private final AuthProperties authProperties = mock(AuthProperties.class);
    private final AccountBlockSessionGuard guard = mock(AccountBlockSessionGuard.class);
    private final Customer customer = new Customer();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LoginController controller = new LoginController(mock(LoginService.class), refreshTokenService,
                customerRepository, jwtIssuer, authProperties, mock(AuditService.class), guard);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new AuthExceptionHandler())
                .build();

        customer.setId(UUID.randomUUID());
        customer.setCountry("ZW");
        customer.setKycTier("basic");
        customer.setCoreExternalId(customer.getId().toString());
        RefreshToken rotated = new RefreshToken();
        rotated.setCustomerId(customer.getId());
        rotated.setSecret(UUID.randomUUID().toString());
        when(refreshTokenService.liveSessionCustomer(SECRET, "device-1")).thenReturn(Optional.of(customer.getId()));
        when(refreshTokenService.rotate(SECRET, "device-1")).thenReturn(rotated);
        when(customerRepository.findById(customer.getId())).thenReturn(Optional.of(customer));
        when(authProperties.accessTokenTtl()).thenReturn(Duration.ofMinutes(10));
        when(jwtIssuer.issue(any())).thenReturn("access-token");
    }

    @Test
    void aFullBlockIs403AccountRestrictedWithFixedCopyAndNoToken() throws Exception {
        doThrow(new AccountRestrictedSessionException()).when(guard).requireSessionAllowed(customer, "device-1");

        mockMvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("account_restricted"))
                .andExpect(jsonPath("$.title").value("Account restricted"))
                .andExpect(jsonPath("$.detail").value(
                        "Your account has been restricted and you have been signed out. Please contact support."))
                .andExpect(jsonPath("$.accessToken").doesNotExist());

        // Nothing is spent or minted: the guard has already revoked every
        // family, this token's included.
        verify(refreshTokenService, never()).rotate(anyString(), anyString());
        verify(jwtIssuer, never()).issue(any());
    }

    @Test
    void theBlockCheckRunsBeforeTheTokenIsSpent() throws Exception {
        // While the core is read the presented token is still unspent, so a
        // check that ends the session, or fails in its own path, has rotated
        // nothing. (Not a defence against a slow core: a client that gives up
        // waiting still has its token spent by step 3, and its retry is a
        // replay. The guard's bounded wait is that defence.)
        mockMvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access-token"));

        InOrder order = inOrder(refreshTokenService, guard, jwtIssuer);
        order.verify(refreshTokenService).liveSessionCustomer(SECRET, "device-1");
        order.verify(guard).requireSessionAllowed(customer, "device-1");
        order.verify(refreshTokenService).rotate(SECRET, "device-1");
        order.verify(jwtIssuer).issue(any());
        // One customer read serves the check and the claims.
        verify(customerRepository).findById(customer.getId());
    }

    @Test
    void aFailureInTheChecksOwnPathSpendsNothing() {
        // What reading before rotating actually buys: the revocation's own
        // transaction failing (the database gone mid-refresh), or any other
        // fault in the guard path, leaves the presented token unspent — the
        // client can present it again once the fault clears. Spent first,
        // its successor would be lost with the error and the retry a replay.
        doThrow(new DataAccessResourceFailureException("connection refused"))
                .when(guard).requireSessionAllowed(customer, "device-1");

        assertThatThrownBy(() -> mockMvc.perform(
                        post("/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(BODY)))
                .hasRootCauseInstanceOf(DataAccessResourceFailureException.class);

        verify(refreshTokenService, never()).rotate(anyString(), anyString());
        verify(jwtIssuer, never()).issue(any());
    }

    @Test
    void aTokenThatIsNotLiveNeverReachesTheCoreAndRotationStillRefusesIt() throws Exception {
        // Unknown, revoked, expired, replayed or wrong-device: the liveness
        // read says no, the core is never read on its behalf (nor is it told
        // about a block), and rotate() refuses it — the one place a replay or
        // device mismatch revokes the family and is audited.
        when(refreshTokenService.liveSessionCustomer(anyString(), anyString())).thenReturn(Optional.empty());
        when(refreshTokenService.rotate(anyString(), anyString())).thenThrow(new RefreshTokenInvalidException());

        mockMvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("refresh_invalid"));

        verifyNoInteractions(guard);
        verify(refreshTokenService).rotate(SECRET, "device-1");
    }

    @Test
    void aRotationThatLosesARaceAfterTheCheckIsStillRefused() throws Exception {
        // Live when read, spent by a concurrent refresh before this one
        // rotates: rotate() decides, exactly as before the check existed.
        when(refreshTokenService.rotate(anyString(), anyString())).thenThrow(new RefreshTokenInvalidException());

        mockMvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("refresh_invalid"));

        verify(jwtIssuer, never()).issue(any());
    }
}
