package zw.co.innbucks.middleware.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import zw.co.innbucks.middleware.otp.ConsoleSmsSender;
import zw.co.innbucks.middleware.otp.SmsSender;

import java.net.http.HttpClient;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Selects and wires the {@link SmsSender}. ONE explicit decision point keyed
 * on {@code innbucks.notify.provider} — deliberately not
 * {@code @ConditionalOnMissingBean} auto-magic, whose outcome would depend on
 * configuration-class processing order.
 *
 * <p>{@code innbucks-gateway} boots the real client (and then every gateway
 * credential is boot-required, fail-fast — same posture as the Fineract cell
 * wiring). {@code console} keeps the no-network stub and logs a LOUD error
 * under a deployment profile: a cell that reaches prod on the stub silently
 * strands every customer at the OTP step.
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(NotifyProperties.class)
public class NotificationConfig {

    private static final Set<String> NON_DEPLOYMENT_PROFILES = Set.of("dev", "test", "it", "local");

    @Bean
    @ConditionalOnProperty(name = "innbucks.notify.provider",
            havingValue = NotifyProperties.PROVIDER_INNBUCKS_GATEWAY)
    public NotificationGatewayClient notificationGatewayClient(NotifyProperties properties,
                                                               ObjectMapper objectMapper,
                                                               Clock authClock) {
        List<String> missing = new java.util.ArrayList<>();
        if (isBlank(properties.baseUrl())) missing.add("innbucks.notify.base-url (env NOTIFY_API_URL)");
        if (isBlank(properties.apiKey())) missing.add("innbucks.notify.api-key (env NOTIFY_API_KEY)");
        if (isBlank(properties.username())) missing.add("innbucks.notify.username (env NOTIFY_API_USERNAME)");
        if (isBlank(properties.password())) missing.add("innbucks.notify.password (env NOTIFY_API_PASSWORD)");
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "innbucks.notify.provider=innbucks-gateway but required settings are blank: "
                    + String.join(", ", missing));
        }

        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        // Credentials/URL trimmed — stray env-var whitespace is a known footgun.
        RestClient restClient = RestClient.builder()
                .baseUrl(properties.baseUrl().trim())
                .requestFactory(requestFactory)
                .build();
        return new NotificationGatewayClient(restClient, properties, objectMapper, authClock);
    }

    /**
     * WhatsApp fallback channel — its own gateway, its own key; only built
     * when explicitly enabled, and then base-url + api-key are boot-required.
     */
    @Bean
    @ConditionalOnProperty(name = "innbucks.notify.whatsapp.enabled", havingValue = "true")
    public WhatsAppNotificationClient whatsAppNotificationClient(NotifyProperties properties) {
        NotifyProperties.Whatsapp wa = properties.whatsapp();
        List<String> missing = new java.util.ArrayList<>();
        if (isBlank(wa.baseUrl())) missing.add("innbucks.notify.whatsapp.base-url (env WHATSAPP_GATEWAY_URL)");
        if (isBlank(wa.apiKey())) missing.add("innbucks.notify.whatsapp.api-key (env WHATSAPP_API_KEY)");
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "innbucks.notify.whatsapp.enabled=true but required settings are blank: "
                    + String.join(", ", missing));
        }
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(wa.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(wa.readTimeout());
        RestClient restClient = RestClient.builder()
                .baseUrl(wa.baseUrl().trim())
                .requestFactory(requestFactory)
                .build();
        return new WhatsAppNotificationClient(restClient, wa);
    }

    @Bean
    public SmsSender smsSender(NotifyProperties properties,
                               ObjectProvider<NotificationGatewayClient> gatewayClient,
                               ObjectProvider<WhatsAppNotificationClient> whatsAppClient,
                               MeterRegistry meterRegistry,
                               Environment environment) {
        String provider = properties.provider() == null
                ? NotifyProperties.PROVIDER_CONSOLE
                : properties.provider().trim().toLowerCase(Locale.ROOT);
        switch (provider) {
            case NotifyProperties.PROVIDER_INNBUCKS_GATEWAY -> {
                WhatsAppNotificationClient fallback = whatsAppClient.getIfAvailable();
                log.info("SmsSender: InnBucks notification gateway at {} (WhatsApp fallback: {})",
                        properties.baseUrl(), fallback == null ? "off" : "on");
                return new NotificationGatewaySmsSender(gatewayClient.getObject(), fallback, meterRegistry);
            }
            case NotifyProperties.PROVIDER_CONSOLE -> {
                if (isDeployment(environment)) {
                    // Not fail-closed: UAT legitimately runs on the stub while the
                    // gateway credentials are provisioned. Loud enough to page on.
                    log.error("════════════════════════════════════════════════════════════════════");
                    log.error(" ⚠️  SMS PROVIDER IS THE CONSOLE STUB under deployment profiles {} —",
                            Arrays.toString(environment.getActiveProfiles()));
                    log.error("     NO customer will receive an OTP. Set NOTIFY_PROVIDER=innbucks-gateway");
                    log.error("     (+ NOTIFY_API_URL/KEY/USERNAME/PASSWORD) before go-live.");
                    log.error("════════════════════════════════════════════════════════════════════");
                }
                return new ConsoleSmsSender();
            }
            default -> throw new IllegalStateException(
                    "Unknown innbucks.notify.provider '" + properties.provider()
                    + "' — expected 'console' or 'innbucks-gateway'");
        }
    }

    private static boolean isDeployment(Environment environment) {
        return Arrays.stream(environment.getActiveProfiles())
                .noneMatch(p -> NON_DEPLOYMENT_PROFILES.contains(p.toLowerCase(Locale.ROOT)));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
