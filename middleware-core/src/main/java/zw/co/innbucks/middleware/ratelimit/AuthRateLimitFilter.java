package zw.co.innbucks.middleware.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import zw.co.innbucks.middleware.ratelimit.RateLimitProperties.Limit;

import java.io.IOException;
import java.net.URI;

/**
 * Per-IP token-bucket throttling on the public auth endpoints, applied before
 * Spring Security so a flood is rejected as cheaply as possible. This adds the
 * per-IP / distributed-brute-force / DoS dimension that the existing controls
 * miss — per-account login backoff ({@code LoginService}) and
 * per-(msisdn,purpose) OTP attempt caps ({@code OtpService}) still layer on top.
 *
 * <p>Ordered just after {@code CorrelationIdFilter} so the 429 carries a
 * correlation ID. Self-filters by path, so it is a cheap pass-through for every
 * non-auth request.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final URI PROBLEM_TYPE = URI.create("about:blank");

    private final RateLimitProperties properties;
    private final RateLimiterService rateLimiter;
    private final ObjectMapper objectMapper;

    public AuthRateLimitFilter(RateLimitProperties properties,
                               RateLimiterService rateLimiter,
                               ObjectMapper objectMapper) {
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!properties.enabled() || !"POST".equalsIgnoreCase(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        Rule rule = ruleFor(request.getRequestURI());
        if (rule == null) {
            chain.doFilter(request, response);
            return;
        }

        String key = "ip:" + rule.name() + ":" + clientIp(request);
        RateLimitDecision decision = rateLimiter.tryConsume(key, rule.limit());
        if (decision.allowed()) {
            chain.doFilter(request, response);
        } else {
            writeTooManyRequests(response, decision.retryAfterSeconds());
        }
    }

    private Rule ruleFor(String path) {
        if (path == null) {
            return null;
        }
        return switch (path) {
            case "/auth/login" -> new Rule("login", properties.ipLogin());
            case "/auth/refresh" -> new Rule("refresh", properties.ipRefresh());
            case "/auth/otp/request" -> new Rule("otp-request", properties.ipOtpRequest());
            case "/auth/otp/verify" -> new Rule("otp-verify", properties.ipOtpVerify());
            case "/auth/pin/set", "/auth/pin/reset" -> new Rule("pin", properties.ipPin());
            default -> null;
        };
    }

    private String clientIp(HttpServletRequest request) {
        if (properties.trustForwardedFor()) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                int comma = forwarded.indexOf(',');
                return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
            }
        }
        return request.getRemoteAddr();
    }

    private void writeTooManyRequests(HttpServletResponse response, long retryAfterSeconds) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
        problem.setType(PROBLEM_TYPE);
        problem.setTitle("Too many requests");
        problem.setProperty("errorCode", "rate_limited");
        problem.setProperty("retryAfterSeconds", retryAfterSeconds);

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), problem);
    }

    private record Rule(String name, Limit limit) {
    }
}
