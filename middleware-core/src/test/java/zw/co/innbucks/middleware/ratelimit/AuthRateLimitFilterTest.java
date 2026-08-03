package zw.co.innbucks.middleware.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import zw.co.innbucks.middleware.anomaly.AuthAnomalyDetector;
import zw.co.innbucks.middleware.ratelimit.RateLimitProperties.Limit;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthRateLimitFilterTest {

    private static final Limit TWO_PER_MIN = new Limit(2, Duration.ofMinutes(1));

    private AuthRateLimitFilter filter(boolean enabled, boolean trustXff) {
        return filter(enabled, trustXff, neverBlocks());
    }

    private AuthRateLimitFilter filter(boolean enabled, boolean trustXff, AuthAnomalyDetector detector) {
        RateLimitProperties props = new RateLimitProperties(
                enabled, trustXff, 1,
                TWO_PER_MIN, TWO_PER_MIN, TWO_PER_MIN, TWO_PER_MIN, TWO_PER_MIN, TWO_PER_MIN, TWO_PER_MIN,
                TWO_PER_MIN);
        return new AuthRateLimitFilter(props, new RateLimiterService(props),
                new ClientIpResolver(props), detector, objectMapper());
    }

    /** The application's mapper, mixin and all — see IdempotencyConfig. */
    private static ObjectMapper objectMapper() {
        return new ObjectMapper()
                .findAndRegisterModules()
                .addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class);
    }

    private static AuthAnomalyDetector neverBlocks() {
        AuthAnomalyDetector detector = mock(AuthAnomalyDetector.class);
        when(detector.isBlocked(anyString())).thenReturn(false);
        return detector;
    }

    private static AuthAnomalyDetector blocking(String sprayingIp, long retryAfterSeconds) {
        AuthAnomalyDetector detector = mock(AuthAnomalyDetector.class);
        when(detector.isBlocked(anyString())).thenReturn(false);
        when(detector.isBlocked(sprayingIp)).thenReturn(true);
        when(detector.remainingBlockSeconds(sprayingIp)).thenReturn(retryAfterSeconds);
        return detector;
    }

    private MockHttpServletResponse post(AuthRateLimitFilter filter, String uri, String ip) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("POST");
        request.setRequestURI(uri);
        request.setRemoteAddr(ip);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void allowsUpToCapacityThenReturns429WithRetryAfter() throws Exception {
        AuthRateLimitFilter filter = filter(true, false);

        assertThat(post(filter, "/auth/login", "10.0.0.1").getStatus()).isEqualTo(200);
        assertThat(post(filter, "/auth/login", "10.0.0.1").getStatus()).isEqualTo(200);

        MockHttpServletResponse blocked = post(filter, "/auth/login", "10.0.0.1");
        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(blocked.getHeader("Retry-After")).isNotBlank();
        assertThat(blocked.getContentType()).contains("application/problem+json");
        // errorCode must be TOP-LEVEL, matching every other error this API
        // returns. Without the ProblemDetail mixin on the mapper it serialises
        // nested under "properties", and a client following the documented
        // "branch on errorCode" rule silently stops recognising rate limiting.
        assertThat(objectMapper().readTree(blocked.getContentAsString()).path("errorCode").asText())
                .isEqualTo("rate_limited");
    }

    @Test
    void differentIpsHaveSeparateBuckets() throws Exception {
        AuthRateLimitFilter filter = filter(true, false);

        post(filter, "/auth/login", "10.0.0.1");
        post(filter, "/auth/login", "10.0.0.1");
        assertThat(post(filter, "/auth/login", "10.0.0.1").getStatus()).isEqualTo(429);
        // Fresh IP is unaffected.
        assertThat(post(filter, "/auth/login", "10.0.0.2").getStatus()).isEqualTo(200);
    }

    @Test
    void unprotectedPathIsAlwaysPassedThrough() throws Exception {
        AuthRateLimitFilter filter = filter(true, false);
        for (int i = 0; i < 10; i++) {
            assertThat(post(filter, "/me/profile", "10.0.0.1").getStatus()).isEqualTo(200);
        }
    }

    @Test
    void getRequestsAreNotRateLimited() throws Exception {
        AuthRateLimitFilter filter = filter(true, false);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("GET");
        request.setRequestURI("/auth/login");
        request.setRemoteAddr("10.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        for (int i = 0; i < 10; i++) {
            filter.doFilter(request, response, new MockFilterChain());
        }
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void disabledLetsEverythingThrough() throws Exception {
        AuthRateLimitFilter filter = filter(false, false);
        for (int i = 0; i < 10; i++) {
            assertThat(post(filter, "/auth/login", "10.0.0.1").getStatus()).isEqualTo(200);
        }
    }

    @Test
    void trustsForwardedForWhenConfigured() throws Exception {
        AuthRateLimitFilter filter = filter(true, true);

        MockHttpServletRequest first = new MockHttpServletRequest();
        first.setMethod("POST");
        first.setRequestURI("/auth/login");
        first.setRemoteAddr("10.0.0.99"); // proxy hop, ignored when XFF trusted
        first.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.99");
        MockHttpServletResponse r1 = new MockHttpServletResponse();
        filter.doFilter(first, r1, new MockFilterChain());
        assertThat(r1.getStatus()).isEqualTo(200);

        // Same forwarded client, second hit ok, third blocked — proving the
        // bucket keys on the XFF client, not the proxy socket address.
        assertThat(postXff(filter, "203.0.113.7").getStatus()).isEqualTo(200);
        assertThat(postXff(filter, "203.0.113.7").getStatus()).isEqualTo(429);
    }

    @Test
    void aSprayingSourceIsTurnedAwayAheadOfItsBucket() throws Exception {
        // 900s, not the bucket's refill — proving the 429 came from the spray
        // block and not from running out of tokens.
        AuthRateLimitFilter filter = filter(true, false, blocking("203.0.113.7", 900L));

        MockHttpServletResponse response = post(filter, "/auth/login", "203.0.113.7");

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("900");
        // Same wire shape as an ordinary bucket rejection: clients already back
        // off on rate_limited, so a blocked source needs no new FE handling.
        assertThat(response.getContentAsString()).contains("rate_limited");
    }

    @Test
    void theSprayBlockDoesNotTouchOtherSources() throws Exception {
        AuthRateLimitFilter filter = filter(true, false, blocking("203.0.113.7", 900L));

        assertThat(post(filter, "/auth/login", "203.0.113.7").getStatus()).isEqualTo(429);
        assertThat(post(filter, "/auth/login", "41.173.193.211").getStatus()).isEqualTo(200);
    }

    @Test
    void theSprayBlockSurvivesTheRateLimiterBeingSwitchedOff() throws Exception {
        // Two independent controls, two independent switches. Turning the
        // buckets off — to debug a proxy-IP problem, say — must not quietly
        // take brute-force blocking down with them.
        AuthRateLimitFilter filter = filter(false, false, blocking("203.0.113.7", 900L));

        assertThat(post(filter, "/auth/login", "203.0.113.7").getStatus()).isEqualTo(429);
        // ...while ordinary callers are unthrottled, as "rate limiting off" means.
        for (int i = 0; i < 10; i++) {
            assertThat(post(filter, "/auth/login", "41.173.193.211").getStatus()).isEqualTo(200);
        }
    }

    @Test
    void aBlockedSourceStillReachesNonAuthEndpoints() throws Exception {
        // Scoped to the auth surface on purpose: a customer sharing an office
        // NAT with an attacker keeps using the token they already hold.
        AuthRateLimitFilter filter = filter(true, false, blocking("203.0.113.7", 900L));

        assertThat(post(filter, "/me/profile", "203.0.113.7").getStatus()).isEqualTo(200);
    }

    private MockHttpServletResponse postXff(AuthRateLimitFilter filter, String forwardedClient) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("POST");
        request.setRequestURI("/auth/login");
        request.setRemoteAddr("10.0.0.99");
        request.addHeader("X-Forwarded-For", forwardedClient + ", 10.0.0.99");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
