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
import zw.co.innbucks.middleware.anomaly.AuthAnomalyDetector;
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
    private final ClientIpResolver clientIpResolver;
    private final AuthAnomalyDetector anomalyDetector;
    private final ObjectMapper objectMapper;

    public AuthRateLimitFilter(RateLimitProperties properties,
                               RateLimiterService rateLimiter,
                               ClientIpResolver clientIpResolver,
                               AuthAnomalyDetector anomalyDetector,
                               ObjectMapper objectMapper) {
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.clientIpResolver = clientIpResolver;
        this.anomalyDetector = anomalyDetector;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        Rule rule = ruleFor(request.getRequestURI());
        if (rule == null) {
            chain.doFilter(request, response);
            return;
        }

        String clientIp = clientIpResolver.resolve(request);

        // A source caught spraying credentials is turned away from the whole
        // auth surface, ahead of its bucket. Scoped to these endpoints on
        // purpose: an already-authenticated customer sharing the address (an
        // office NAT) keeps working with the token they already hold.
        //
        // Checked BEFORE the rate-limit master switch, and deliberately not
        // gated by it: throttling and spray-blocking are separate controls with
        // separate switches (innbucks.security.anomaly.*). Turning the buckets
        // off — to debug a proxy-IP problem, say — must not quietly disable
        // brute-force blocking as a side effect.
        long blockedFor = anomalyDetector.isBlocked(clientIp)
                ? anomalyDetector.remainingBlockSeconds(clientIp) : 0L;
        if (blockedFor > 0) {
            // Same 429 + Retry-After + rate_limited shape as an ordinary bucket
            // rejection: clients already back off correctly on it, and it tells
            // an attacker nothing they cannot infer anyway.
            writeTooManyRequests(response, blockedFor);
            return;
        }

        if (!properties.enabled()) {
            chain.doFilter(request, response);
            return;
        }

        String key = "ip:" + rule.name() + ":" + clientIp;
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
            // Step-up shares the OTP buckets: same SMS cost profile, same abuse
            // shape — being authenticated bounds but doesn't remove it.
            case "/auth/step-up/request" -> new Rule("otp-request", properties.ipOtpRequest());
            case "/auth/step-up/verify" -> new Rule("otp-verify", properties.ipOtpVerify());
            case "/auth/pin/set", "/auth/pin/reset" -> new Rule("pin", properties.ipPin());
            // Public + creates core-banking state: the tightest bucket.
            case "/register" -> new Rule("register", properties.ipRegister());
            default -> null;
        };
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
