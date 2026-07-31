package zw.co.innbucks.middleware.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import zw.co.innbucks.middleware.ratelimit.RateLimitProperties.Limit;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AuthRateLimitFilterTest {

    private static final Limit TWO_PER_MIN = new Limit(2, Duration.ofMinutes(1));

    private AuthRateLimitFilter filter(boolean enabled, boolean trustXff) {
        RateLimitProperties props = new RateLimitProperties(
                enabled, trustXff, 1,
                TWO_PER_MIN, TWO_PER_MIN, TWO_PER_MIN, TWO_PER_MIN, TWO_PER_MIN, TWO_PER_MIN, TWO_PER_MIN);
        return new AuthRateLimitFilter(props, new RateLimiterService(props),
                new ClientIpResolver(props), new ObjectMapper());
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
        assertThat(blocked.getContentAsString()).contains("rate_limited");
        assertThat(blocked.getContentType()).contains("application/problem+json");
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
