package zw.co.innbucks.middleware.fineract;

import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

/**
 * Builds the client EXACTLY the shape {@link FineractAdapterConfig} produces
 * (same headers, factory, timeouts), pointed at WireMock. Pure JUnit — no
 * Spring context, per the contract-test convention.
 */
public final class FineractContractTestSupport {

    static final String READ_USER = "innbucks-mw-read";
    static final String READ_PASS = "read-secret";
    static final String WRITE_USER = "innbucks-mw-write";
    static final String WRITE_PASS = "write-secret";
    static final String TENANT = "default";
    static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-07-30T10:00:00Z"), ZoneOffset.UTC);

    private FineractContractTestSupport() {
    }

    public static FineractProperties properties(int port) {
        return propertiesWithCoreEventsToken(port, "test-core-events-token");
    }

    public static FineractProperties propertiesWithCoreEventsToken(int port, String coreEventsToken) {
        return new FineractProperties(
                "http://localhost:" + port,
                TENANT,
                READ_USER, READ_PASS,
                WRITE_USER, WRITE_PASS,
                1L, 3L, 7L, "KES", coreEventsToken, "en", "yyyy-MM-dd",
                Duration.ofSeconds(2), Duration.ofSeconds(5),
                new FineractProperties.Resilience(false, 20, 10, 50,
                        Duration.ofSeconds(30), 3, Duration.ofSeconds(8), 3, Duration.ofMillis(50)));
    }

    public static FineractClient client(FineractProperties properties) {
        return new FineractClient(
                restClient(properties, READ_USER, READ_PASS),
                restClient(properties, WRITE_USER, WRITE_PASS),
                FineractResilience.passthrough(),
                properties,
                FIXED_CLOCK);
    }

    static String basicAuth(String user, String pass) {
        return "Basic " + Base64.getEncoder().encodeToString(
                (user + ":" + pass).getBytes(StandardCharsets.UTF_8));
    }

    private static RestClient restClient(FineractProperties properties, String user, String pass) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, basicAuth(user, pass))
                .defaultHeader("Fineract-Platform-TenantId", properties.tenantId())
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .requestInterceptor(new FineractCorrelationInterceptor())
                .build();
    }
}
