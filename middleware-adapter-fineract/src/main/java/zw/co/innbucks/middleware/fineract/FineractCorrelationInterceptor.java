package zw.co.innbucks.middleware.fineract;

import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * Propagates the middleware's correlation id (from MDC, set by
 * CorrelationIdFilter) to Fineract as {@code X-Correlation-ID}, so one id
 * follows a request from the mobile app through the middleware into the
 * core's logs.
 */
public class FineractCorrelationInterceptor implements ClientHttpRequestInterceptor {

    static final String HEADER = "X-Correlation-ID";
    private static final String MDC_KEY = "correlationId";

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        String correlationId = MDC.get(MDC_KEY);
        if (correlationId != null && !correlationId.isBlank()) {
            request.getHeaders().set(HEADER, correlationId);
        }
        return execution.execute(request, body);
    }
}
