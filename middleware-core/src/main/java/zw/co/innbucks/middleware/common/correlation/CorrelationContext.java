package zw.co.innbucks.middleware.common.correlation;

import org.slf4j.MDC;

import java.util.UUID;

public final class CorrelationContext {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "correlationId";
    public static final String COUNTRY_MDC_KEY = "country";

    private CorrelationContext() {
    }

    public static String currentOrNew() {
        String existing = MDC.get(MDC_KEY);
        return (existing == null || existing.isBlank()) ? UUID.randomUUID().toString() : existing;
    }

    public static String current() {
        return MDC.get(MDC_KEY);
    }
}
