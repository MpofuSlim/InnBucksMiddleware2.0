package zw.co.innbucks.middleware.fineract;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;

/**
 * Wires the Fineract adapter ONLY when this cell fronts Fineract
 * ({@code innbucks.core.provider=fineract}) — and then every
 * {@code fineract.*} property is boot-required (fail-fast, like
 * {@code INNBUCKS_COUNTRY}). Two RestClients, one per AppUser: reads ride
 * the read-only credential, commands the write credential, so a leaked
 * reconciliation credential cannot move money.
 */
@Configuration
@ConditionalOnProperty(name = "innbucks.core.provider", havingValue = "fineract")
@EnableConfigurationProperties(FineractProperties.class)
public class FineractAdapterConfig {

    @Bean
    public FineractResilience fineractResilience(FineractProperties properties) {
        return new FineractResilience(properties.resilience());
    }

    @Bean
    public FineractClient fineractClient(FineractProperties properties,
                                         FineractResilience fineractResilience,
                                         Clock clock) {
        RestClient readClient = buildClient(properties,
                properties.readUsername(), properties.readPassword());
        RestClient writeClient = buildClient(properties,
                properties.writeUsername(), properties.writePassword());
        return new FineractClient(readClient, writeClient, fineractResilience, properties, clock);
    }

    @Bean
    public FineractAdapter fineractAdapter(FineractClient fineractClient,
                                           FineractProperties properties) {
        return new FineractAdapter(fineractClient, properties);
    }

    private RestClient buildClient(FineractProperties properties, String username, String password) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        // Credentials trimmed — stray env-var whitespace is a known footgun.
        String basic = Base64.getEncoder().encodeToString(
                (username.trim() + ":" + password.trim()).getBytes(StandardCharsets.UTF_8));
        return RestClient.builder()
                .baseUrl(properties.baseUrl().trim())
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                .defaultHeader("Fineract-Platform-TenantId", properties.tenantId().trim())
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .requestInterceptor(new FineractCorrelationInterceptor())
                .build();
    }
}
