package zw.co.innbucks.middleware.common.correlation;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import zw.co.innbucks.middleware.common.country.CountryProperties;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    /**
     * Allowed shape of an inbound correlation ID. Conservative: ASCII letters,
     * digits, dash, underscore, dot, colon — the same set our own UUIDs +
     * slug callers produce. 64 chars is enough for two UUIDs joined with a
     * separator and well under any reasonable header-length budget.
     *
     * <p>Rejecting anything else closes two attack vectors:
     * <ul>
     *   <li>Log-injection — a header value of {@code "id\nINJECTED-ERROR"}
     *       would land in MDC, then in every log line via the configured
     *       {@code %X{correlationId}} pattern; the newline would split a
     *       single log line into two and the injected half would look
     *       indistinguishable from real log output to anyone tailing the
     *       stream.</li>
     *   <li>Response-header pollution — the header is echoed back via
     *       {@code response.setHeader(...)}, so a multi-megabyte value both
     *       wastes bandwidth and risks bypassing downstream proxies that
     *       cap header sizes.</li>
     * </ul>
     */
    private static final Pattern VALID_CORRELATION_ID = Pattern.compile("^[A-Za-z0-9_.:-]{1,64}$");

    private final String country;

    public CorrelationIdFilter(CountryProperties countryProperties) {
        this.country = countryProperties.country().name();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String incoming = request.getHeader(CorrelationContext.HEADER);
        String id;
        if (incoming != null && !incoming.isBlank() && VALID_CORRELATION_ID.matcher(incoming).matches()) {
            id = incoming;
        } else {
            // Either no header, blank header, or a header that didn't match
            // the safe character/length window — substitute a fresh UUID so
            // every request still has a usable correlation ID without
            // honouring an attacker-controlled value.
            id = UUID.randomUUID().toString();
        }

        MDC.put(CorrelationContext.MDC_KEY, id);
        MDC.put(CorrelationContext.COUNTRY_MDC_KEY, country);
        response.setHeader(CorrelationContext.HEADER, id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(CorrelationContext.MDC_KEY);
            MDC.remove(CorrelationContext.COUNTRY_MDC_KEY);
        }
    }
}
