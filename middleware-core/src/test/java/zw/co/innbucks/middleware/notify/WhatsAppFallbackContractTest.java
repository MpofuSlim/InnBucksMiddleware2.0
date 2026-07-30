package zw.co.innbucks.middleware.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract test for the SMS→WhatsApp fallback chain in
 * {@link NotificationGatewaySmsSender}: when the SMS gateway rejects a send,
 * the same message rides the external WhatsApp gateway's
 * {@code POST /api/messages/custom-notification} (wire shape ported from the
 * ticketing fleet's proven client: {@code {to, notification}}, lowercase
 * {@code x-api-key} header). One WireMock hosts both gateways — the paths
 * never collide.
 */
class WhatsAppFallbackContractTest {

    private static final String SMS_API_KEY = "sms-api-key";
    private static final String WA_API_KEY = "wa-api-key";

    private static WireMockServer wireMock;

    private SimpleMeterRegistry meterRegistry;
    private NotificationGatewayClient smsClient;
    private WhatsAppNotificationClient whatsAppClient;

    @BeforeAll
    static void startServer() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopServer() {
        wireMock.stop();
    }

    @BeforeEach
    void setUp() {
        wireMock.resetAll();
        meterRegistry = new SimpleMeterRegistry();
        String baseUrl = "http://localhost:" + wireMock.port();
        NotifyProperties props = new NotifyProperties(
                NotifyProperties.PROVIDER_INNBUCKS_GATEWAY,
                baseUrl, SMS_API_KEY, "user", "pass",
                Duration.ofMinutes(10), Duration.ofSeconds(1), Duration.ofSeconds(2),
                new NotifyProperties.Whatsapp(true, baseUrl, WA_API_KEY,
                        Duration.ofSeconds(1), Duration.ofSeconds(2)));
        smsClient = new NotificationGatewayClient(
                restClient(baseUrl), props, new ObjectMapper(), Clock.systemUTC());
        whatsAppClient = new WhatsAppNotificationClient(restClient(baseUrl), props.whatsapp());

        String token = jwt(Instant.now().plusSeconds(3600));
        wireMock.stubFor(post(urlEqualTo("/auth/third-party"))
                .willReturn(okJson("{\"accessToken\":\"" + token + "\"}")));
    }

    private static RestClient restClient(String baseUrl) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(1))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(2));
        return RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    private static String jwt(Instant exp) {
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"exp\":" + exp.getEpochSecond() + "}").getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".sig";
    }

    private NotificationGatewaySmsSender senderWithFallback() {
        return new NotificationGatewaySmsSender(smsClient, whatsAppClient, meterRegistry);
    }

    private double counter(String outcome) {
        return meterRegistry.counter("innbucks.sms.sent", "outcome", outcome).count();
    }

    @Test
    void smsFailureFallsBackToWhatsAppWithThePinnedWireShape() {
        wireMock.stubFor(post(urlEqualTo("/api/notification/sms"))
                .willReturn(aResponse().withStatus(500)));
        wireMock.stubFor(post(urlEqualTo("/api/messages/custom-notification"))
                .willReturn(aResponse().withStatus(200)));

        // '!' would be transliterated on the SMS wire; WhatsApp must get the
        // ORIGINAL body — sanitisation is an SMS-gateway rule, not ours.
        senderWithFallback().send("+254712345678", "Your code is 123456. Do not share it!");

        wireMock.verify(postRequestedFor(urlEqualTo("/api/messages/custom-notification"))
                .withHeader("x-api-key", equalTo(WA_API_KEY))
                .withRequestBody(matchingJsonPath("$.to", equalTo("+254712345678")))
                .withRequestBody(matchingJsonPath("$.notification",
                        equalTo("Your code is 123456. Do not share it!"))));
        assertThat(counter("whatsapp_fallback")).isEqualTo(1.0);
        assertThat(counter("failure")).isZero();
    }

    @Test
    void smsSuccessNeverTouchesWhatsApp() {
        wireMock.stubFor(post(urlEqualTo("/api/notification/sms"))
                .willReturn(aResponse().withStatus(200)));

        senderWithFallback().send("+254712345678", "hello");

        wireMock.verify(0, postRequestedFor(urlEqualTo("/api/messages/custom-notification")));
        assertThat(counter("success")).isEqualTo(1.0);
    }

    @Test
    void bothChannelsFailingIsADeliveryFailureCarryingBothLegs() {
        wireMock.stubFor(post(urlEqualTo("/api/notification/sms"))
                .willReturn(aResponse().withStatus(500)));
        wireMock.stubFor(post(urlEqualTo("/api/messages/custom-notification"))
                .willReturn(aResponse().withStatus(502)));

        assertThatThrownBy(() -> senderWithFallback().send("+254712345678", "hello"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("HTTP 500")
                .satisfies(ex -> assertThat(ex.getSuppressed())
                        .singleElement()
                        .isInstanceOf(NotificationDeliveryException.class));
        wireMock.verify(1, postRequestedFor(urlEqualTo("/api/messages/custom-notification")));
        assertThat(counter("failure")).isEqualTo(1.0);
    }

    @Test
    void withoutAConfiguredFallbackTheSmsFailurePropagates() {
        wireMock.stubFor(post(urlEqualTo("/api/notification/sms"))
                .willReturn(aResponse().withStatus(500)));

        NotificationGatewaySmsSender noFallback =
                new NotificationGatewaySmsSender(smsClient, null, meterRegistry);
        assertThatThrownBy(() -> noFallback.send("+254712345678", "hello"))
                .isInstanceOf(NotificationDeliveryException.class);
        wireMock.verify(0, postRequestedFor(urlEqualTo("/api/messages/custom-notification")));
        assertThat(counter("failure")).isEqualTo(1.0);
    }

    @Test
    void oversizedWhatsAppNotificationIsRefusedBeforeTheWire() {
        assertThatThrownBy(() -> whatsAppClient.sendCustomNotification(
                "+254712345678", "x".repeat(1601)))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("1600");
        wireMock.verify(0, postRequestedFor(urlEqualTo("/api/messages/custom-notification")));
    }

    @Test
    void whatsAppConnectRefusedSurfacesAsDeliveryFailure() {
        WhatsAppNotificationClient dead = new WhatsAppNotificationClient(
                restClient("http://localhost:1"),
                new NotifyProperties.Whatsapp(true, "http://localhost:1", WA_API_KEY,
                        Duration.ofSeconds(1), Duration.ofSeconds(1)));
        assertThatThrownBy(() -> dead.sendCustomNotification("+254712345678", "hello"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("unreachable");
    }
}
