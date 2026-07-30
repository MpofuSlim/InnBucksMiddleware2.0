package zw.co.innbucks.middleware.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
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
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToIgnoreCase;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract test for {@link NotificationGatewayClient} against the InnBucks
 * notification API's observed wire shapes (ported from the ticketing fleet's
 * canonical {@code SmsNotificationClientContractTest}). Pure JUnit + WireMock,
 * no Spring context: the client is constructed exactly as
 * {@code NotificationConfig} builds it, pointed at WireMock's port.
 */
class NotificationGatewayClientContractTest {

    private static final String API_KEY = "test-api-key";
    private static final String USERNAME = "mw-third-party";
    private static final String PASSWORD = "mw-secret";

    private static WireMockServer wireMock;

    private NotificationGatewayClient client;

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
        client = newClient(properties("http://localhost:" + wireMock.port()));
    }

    private static NotifyProperties properties(String baseUrl) {
        return new NotifyProperties(
                NotifyProperties.PROVIDER_INNBUCKS_GATEWAY,
                baseUrl, API_KEY, USERNAME, PASSWORD,
                Duration.ofMinutes(10), Duration.ofSeconds(1), Duration.ofSeconds(2),
                new NotifyProperties.Whatsapp(false, null, null,
                        Duration.ofSeconds(1), Duration.ofSeconds(2)));
    }

    /** Built the same way NotificationConfig builds the production client. */
    private static NotificationGatewayClient newClient(NotifyProperties props) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(props.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(props.readTimeout());
        RestClient restClient = RestClient.builder()
                .baseUrl(props.baseUrl() == null ? "http://localhost:1" : props.baseUrl().trim())
                .requestFactory(requestFactory)
                .build();
        return new NotificationGatewayClient(restClient, props, new ObjectMapper(), Clock.systemUTC());
    }

    /** Unsigned JWT-shaped token whose payload carries the given exp. */
    private static String jwtWithExp(Instant exp) {
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"exp\":" + exp.getEpochSecond() + "}").getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".sig";
    }

    private void stubLogin(String token) {
        wireMock.stubFor(post(urlEqualTo("/auth/third-party"))
                .willReturn(okJson("{\"accessToken\":\"" + token + "\"}")));
    }

    // ── SMS ────────────────────────────────────────────────────────────────

    @Test
    void happySmsLogsInThenPostsThePinnedWireShape() {
        String token = jwtWithExp(Instant.now().plusSeconds(3600));
        stubLogin(token);
        wireMock.stubFor(post(urlEqualTo("/api/notification/sms")).willReturn(aResponse().withStatus(200)));

        client.sendSms("+254712345678", "Your Innbucks PIN setup code is 123456. Do not share it with anyone.",
                "IBMW-SMS-fixed-ref");

        wireMock.verify(postRequestedFor(urlEqualTo("/auth/third-party"))
                .withHeader("X-Api-Key", equalTo(API_KEY))
                .withRequestBody(matchingJsonPath("$.username", equalTo(USERNAME)))
                .withRequestBody(matchingJsonPath("$.password", equalTo(PASSWORD))));
        wireMock.verify(postRequestedFor(urlEqualTo("/api/notification/sms"))
                .withHeader("X-Api-Key", equalTo(API_KEY))
                .withHeader("Authorization", equalTo("Bearer " + token))
                .withHeader("Content-Type", equalToIgnoreCase("application/json"))
                .withRequestBody(matchingJsonPath("$.message",
                        equalTo("Your Innbucks PIN setup code is 123456. Do not share it with anyone.")))
                .withRequestBody(matchingJsonPath("$.reference", equalTo("IBMW-SMS-fixed-ref")))
                .withRequestBody(matchingJsonPath("$.destinationMsisdn", equalTo("+254712345678"))));
    }

    @Test
    void bearerTokenIsCachedUntilItsJwtExp() {
        stubLogin(jwtWithExp(Instant.now().plusSeconds(3600)));
        wireMock.stubFor(post(urlEqualTo("/api/notification/sms")).willReturn(aResponse().withStatus(200)));

        client.sendSms("+254712345678", "first", null);
        client.sendSms("+254712345678", "second", null);

        wireMock.verify(1, postRequestedFor(urlEqualTo("/auth/third-party")));
        wireMock.verify(2, postRequestedFor(urlEqualTo("/api/notification/sms")));
    }

    @Test
    void blankReferenceIsAutoFilledWithTheMiddlewarePrefix() {
        stubLogin(jwtWithExp(Instant.now().plusSeconds(3600)));
        wireMock.stubFor(post(urlEqualTo("/api/notification/sms")).willReturn(aResponse().withStatus(200)));

        client.sendSms("+254712345678", "hello", null);

        wireMock.verify(postRequestedFor(urlEqualTo("/api/notification/sms"))
                .withRequestBody(matchingJsonPath("$.reference", matching("^IBMW-SMS-[0-9a-f-]{36}$"))));
    }

    @Test
    void smsBodyIsTransliteratedToTheGatewaysAcceptedCharset() {
        stubLogin(jwtWithExp(Instant.now().plusSeconds(3600)));
        wireMock.stubFor(post(urlEqualTo("/api/notification/sms")).willReturn(aResponse().withStatus(200)));

        // Em-dash and '!' are both live-confirmed 400s on this gateway.
        client.sendSms("+254712345678", "Approved — do not share!", null);

        wireMock.verify(postRequestedFor(urlEqualTo("/api/notification/sms"))
                .withRequestBody(matchingJsonPath("$.message", equalTo("Approved - do not share."))));
    }

    @Test
    void a401IsRetriedOnceWithAFreshTokenThenSucceeds() {
        String stale = jwtWithExp(Instant.now().plusSeconds(3600));
        String fresh = jwtWithExp(Instant.now().plusSeconds(7200));
        // Login scenario: the first login hands out the (server-side revoked)
        // stale token; the forced re-login after the 401 hands out a fresh one.
        wireMock.stubFor(post(urlEqualTo("/auth/third-party")).inScenario("relogin")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(okJson("{\"accessToken\":\"" + stale + "\"}"))
                .willSetStateTo("refreshed"));
        wireMock.stubFor(post(urlEqualTo("/auth/third-party")).inScenario("relogin")
                .whenScenarioStateIs("refreshed")
                .willReturn(okJson("{\"accessToken\":\"" + fresh + "\"}")));
        wireMock.stubFor(post(urlEqualTo("/api/notification/sms"))
                .withHeader("Authorization", equalTo("Bearer " + stale))
                .willReturn(aResponse().withStatus(401)));
        wireMock.stubFor(post(urlEqualTo("/api/notification/sms"))
                .withHeader("Authorization", equalTo("Bearer " + fresh))
                .willReturn(aResponse().withStatus(200)));

        client.sendSms("+254712345678", "hello", null);

        wireMock.verify(2, postRequestedFor(urlEqualTo("/api/notification/sms")));
        wireMock.verify(2, postRequestedFor(urlEqualTo("/auth/third-party")));
    }

    @Test
    void persistent401GivesUpAfterExactlyOneReplay() {
        stubLogin(jwtWithExp(Instant.now().plusSeconds(3600)));
        wireMock.stubFor(post(urlEqualTo("/api/notification/sms")).willReturn(aResponse().withStatus(401)));

        assertThatThrownBy(() -> client.sendSms("+254712345678", "hello", null))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("twice");
        wireMock.verify(2, postRequestedFor(urlEqualTo("/api/notification/sms")));
    }

    @Test
    void rejectedLoginSurfacesTheStatusNotAnOpaqueError() {
        wireMock.stubFor(post(urlEqualTo("/auth/third-party"))
                .willReturn(aResponse().withStatus(401)
                        .withBody("{\"errors\":[\"Invalid username\"]}")));

        assertThatThrownBy(() -> client.sendSms("+254712345678", "hello", null))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("login failed: HTTP 401");
        wireMock.verify(0, postRequestedFor(urlEqualTo("/api/notification/sms")));
    }

    @Test
    void loginWithoutAnAccessTokenIsADeliveryFailure() {
        stubLogin("");

        assertThatThrownBy(() -> client.sendSms("+254712345678", "hello", null))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("no accessToken");
    }

    @Test
    void serverErrorIsNotBlindlyRetried() {
        stubLogin(jwtWithExp(Instant.now().plusSeconds(3600)));
        wireMock.stubFor(post(urlEqualTo("/api/notification/sms"))
                .willReturn(aResponse().withStatus(500).withBody("{\"errors\":[\"boom\"]}")));

        assertThatThrownBy(() -> client.sendSms("+254712345678", "hello", null))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("HTTP 500");
        wireMock.verify(1, postRequestedFor(urlEqualTo("/api/notification/sms")));
    }

    @Test
    void blankInputsNeverTouchTheWire() {
        assertThatThrownBy(() -> client.sendSms(" ", "hello", null))
                .isInstanceOf(NotificationDeliveryException.class);
        assertThatThrownBy(() -> client.sendSms("+254712345678", " ", null))
                .isInstanceOf(NotificationDeliveryException.class);
        wireMock.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void missingConfigurationFailsBeforeAnyNetworkCall() {
        NotificationGatewayClient unconfigured = newClient(new NotifyProperties(
                NotifyProperties.PROVIDER_INNBUCKS_GATEWAY,
                "http://localhost:" + wireMock.port(), " ", USERNAME, PASSWORD,
                Duration.ofMinutes(10), Duration.ofSeconds(1), Duration.ofSeconds(2),
                new NotifyProperties.Whatsapp(false, null, null,
                        Duration.ofSeconds(1), Duration.ofSeconds(2))));

        assertThatThrownBy(() -> unconfigured.sendSms("+254712345678", "hello", null))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("not configured");
        wireMock.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void connectRefusedSurfacesAsDeliveryFailure() {
        // Separate client at a known-closed port — never stop/restart the
        // shared WireMock (a restart gets a different dynamic port and breaks
        // the other tests).
        NotificationGatewayClient dead = newClient(properties("http://localhost:1"));

        assertThatThrownBy(() -> dead.sendSms("+254712345678", "hello", null))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("Unable to reach");
    }

    // ── Email (the "later on for my emails too" surface, pinned now) ──────

    @Test
    void happyEmailPostsThePinnedWireShape() {
        String token = jwtWithExp(Instant.now().plusSeconds(3600));
        stubLogin(token);
        wireMock.stubFor(post(urlEqualTo("/api/notification/email")).willReturn(aResponse().withStatus(200)));

        // Subject is charset-validated by the gateway -> transliterated; the
        // BODY keeps its Unicode typography.
        client.sendEmail("customer@example.com", "Welcome — InnBucks", "Karibu — welcome aboard!", null);

        wireMock.verify(postRequestedFor(urlEqualTo("/api/notification/email"))
                .withHeader("X-Api-Key", equalTo(API_KEY))
                .withHeader("Authorization", equalTo("Bearer " + token))
                .withRequestBody(matchingJsonPath("$.subject", equalTo("Welcome - InnBucks")))
                .withRequestBody(matchingJsonPath("$.message", equalTo("Karibu — welcome aboard!")))
                .withRequestBody(matchingJsonPath("$.reference", matching("^IBMW-EMAIL-[0-9a-f-]{36}$")))
                .withRequestBody(matchingJsonPath("$.destinationEmail", equalTo("customer@example.com"))));
    }

    @Test
    void blankEmailInputsNeverTouchTheWire() {
        assertThatThrownBy(() -> client.sendEmail(" ", "s", "m", null))
                .isInstanceOf(NotificationDeliveryException.class);
        assertThatThrownBy(() -> client.sendEmail("a@b.c", " ", "m", null))
                .isInstanceOf(NotificationDeliveryException.class);
        assertThatThrownBy(() -> client.sendEmail("a@b.c", "s", " ", null))
                .isInstanceOf(NotificationDeliveryException.class);
        wireMock.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void opaqueTokenFallsBackToConfiguredTtlWithoutFailing() {
        // Not JWT-shaped at all — expiry derives from token-ttl config instead.
        stubLogin("opaque-token-value");
        wireMock.stubFor(post(urlEqualTo("/api/notification/sms")).willReturn(aResponse().withStatus(200)));

        client.sendSms("+254712345678", "hello", null);

        wireMock.verify(postRequestedFor(urlEqualTo("/api/notification/sms"))
                .withHeader("Authorization", equalTo("Bearer opaque-token-value")));
        assertThat(wireMock.findAll(postRequestedFor(urlEqualTo("/auth/third-party")))).hasSize(1);
    }
}
