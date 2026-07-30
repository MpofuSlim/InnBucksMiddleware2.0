package zw.co.innbucks.middleware.notify;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Sends SMS (and, when a use case lands, plain-text email) through the
 * InnBucks public notification API — the same platform gateway the ticketing
 * fleet delivers through, reused here per the platform decision to
 * standardise on it. Ported from ticketing's proven
 * {@code EmailNotificationClient}; the wire contract is pinned by
 * {@code NotificationGatewayClientContractTest}.
 *
 * <p>Auth: an {@code X-Api-Key} header on every call plus a bearer token from
 * {@code POST /auth/third-party} ({@code {username, password}} →
 * {@code {accessToken}}), cached until the JWT's {@code exp} minus a 30s
 * safety margin and refreshed once on a 401 before giving up.
 *
 * <p>Wire bodies: SMS {@code {message, reference, destinationMsisdn}} at
 * {@code POST /api/notification/sms}; email
 * {@code {subject, message, reference, destinationEmail}} at
 * {@code POST /api/notification/email}. A blank reference is auto-filled
 * ({@code IBMW-SMS-<uuid>} / {@code IBMW-EMAIL-<uuid>}) so every send is
 * traceable against the gateway's logs.
 *
 * <p>Never logs the message body (it carries OTP codes) or the raw MSISDN.
 * Every failure — rejection, connectivity, missing config, blank input —
 * surfaces as {@link NotificationDeliveryException}.
 */
@Slf4j
public class NotificationGatewayClient {

    static final String LOGIN_PATH = "/auth/third-party";
    static final String SMS_PATH = "/api/notification/sms";
    static final String EMAIL_PATH = "/api/notification/email";
    static final String API_KEY_HEADER = "X-Api-Key";
    static final String SMS_REFERENCE_PREFIX = "IBMW-SMS-";
    static final String EMAIL_REFERENCE_PREFIX = "IBMW-EMAIL-";

    private final RestClient restClient;
    private final NotifyProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    private String accessToken;
    private Instant tokenExpiry = Instant.EPOCH;

    public NotificationGatewayClient(RestClient restClient,
                                     NotifyProperties properties,
                                     ObjectMapper objectMapper,
                                     Clock clock) {
        this.restClient = restClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Send a plain-text SMS to a single MSISDN. The body is transliterated to
     * the gateway's accepted character set ({@link SmsTextSanitizer}) — the
     * gateway 400-rejects anything outside it.
     *
     * @throws NotificationDeliveryException on blank input, missing config, an
     *         upstream rejection, or a connectivity failure.
     */
    public void sendSms(String destinationMsisdn, String message, String reference) {
        if (isBlank(destinationMsisdn)) {
            throw new NotificationDeliveryException("SMS recipient is blank");
        }
        if (isBlank(message)) {
            throw new NotificationDeliveryException("SMS message is blank");
        }
        requireConfigured();
        String ref = isBlank(reference) ? SMS_REFERENCE_PREFIX + UUID.randomUUID() : reference;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", SmsTextSanitizer.toGsmSafe(message));
        payload.put("reference", ref);
        payload.put("destinationMsisdn", destinationMsisdn);

        post(SMS_PATH, payload, "SMS", ref);
        log.info("SMS accepted by notification API ref={}", ref);
    }

    /**
     * Send a plain-text email to a single recipient. The SUBJECT is
     * transliterated (the gateway charset-validates it and answers
     * {@code 400 {"errors":["Invalid subject"]}} for non-ASCII); the BODY is
     * deliberately not — the endpoint accepts Unicode there.
     *
     * @throws NotificationDeliveryException on blank input, missing config, an
     *         upstream rejection, or a connectivity failure.
     */
    public void sendEmail(String destinationEmail, String subject, String message, String reference) {
        if (isBlank(destinationEmail)) {
            throw new NotificationDeliveryException("Email recipient is blank");
        }
        if (isBlank(subject)) {
            throw new NotificationDeliveryException("Email subject is blank");
        }
        if (isBlank(message)) {
            throw new NotificationDeliveryException("Email message is blank");
        }
        requireConfigured();
        String ref = isBlank(reference) ? EMAIL_REFERENCE_PREFIX + UUID.randomUUID() : reference;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("subject", SmsTextSanitizer.toGsmSafe(subject));
        payload.put("message", message);
        payload.put("reference", ref);
        payload.put("destinationEmail", destinationEmail);

        post(EMAIL_PATH, payload, "email", ref);
        log.info("Email accepted by notification API ref={}", ref);
    }

    private void post(String path, Map<String, Object> payload, String kind, String ref) {
        withAuthRetryOn401(token -> {
            try {
                restClient.post()
                        .uri(path)
                        .header(API_KEY_HEADER, properties.apiKey())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .body(payload)
                        .retrieve()
                        .toBodilessEntity();
                return null;
            } catch (RestClientResponseException ex) {
                if (ex.getStatusCode().value() == 401) {
                    throw new UnauthorizedException();
                }
                // The upstream body is bounded, carries no credential we sent,
                // and names the real reason (e.g. {"errors":["Invalid message"]}).
                log.warn("Notification API rejected {} ref={} status={} body={}",
                        kind, ref, ex.getStatusCode(), ex.getResponseBodyAsString());
                throw new NotificationDeliveryException(
                        "Notification API rejected " + kind + ": HTTP " + ex.getStatusCode().value(), ex);
            } catch (NotificationDeliveryException ex) {
                throw ex;
            } catch (RuntimeException ex) {
                log.warn("Notification API unreachable for {} ref={} error={}", kind, ref, ex.getMessage());
                throw new NotificationDeliveryException(
                        "Notification API unreachable: " + ex.getMessage(), ex);
            }
        });
    }

    /** Run an authed call; on 401, force one token refresh and replay once. */
    private <T> T withAuthRetryOn401(Function<String, T> call) {
        try {
            return call.apply(currentToken(false));
        } catch (UnauthorizedException first) {
            log.info("Notification API returned 401 — refreshing token and replaying once");
            try {
                return call.apply(currentToken(true));
            } catch (UnauthorizedException second) {
                throw new NotificationDeliveryException(
                        "Notification API rejected our credentials twice (401) — "
                        + "check NOTIFY_API_USERNAME/NOTIFY_API_PASSWORD/NOTIFY_API_KEY");
            }
        }
    }

    private synchronized String currentToken(boolean force) {
        if (!force && accessToken != null && clock.instant().isBefore(tokenExpiry)) {
            return accessToken;
        }
        try {
            String raw = restClient.post()
                    .uri(LOGIN_PATH)
                    .header(API_KEY_HEADER, properties.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(Map.of("username", properties.username(),
                            "password", properties.password()))
                    .retrieve()
                    .body(String.class);
            Map<String, Object> parsed = parseJson(raw);
            Object token = parsed.get("accessToken");
            if (token == null || token.toString().isBlank()) {
                throw new NotificationDeliveryException("Notification API login returned no accessToken");
            }
            accessToken = token.toString();
            tokenExpiry = deriveExpiry(accessToken).minusSeconds(30);
            log.info("Notification API login succeeded; token cached until {}", tokenExpiry);
            return accessToken;
        } catch (RestClientResponseException e) {
            // Surface the upstream body — a bare "HTTP 4xx" hides the real
            // reason (e.g. {"errors":["Invalid username"]}) and cost the
            // ticketing team a multi-hour wild goose chase in production.
            log.warn("Notification API rejected login status={} body={}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            throw new NotificationDeliveryException(
                    "Notification API login failed: HTTP " + e.getStatusCode().value(), e);
        } catch (NotificationDeliveryException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("Notification API unreachable for login: {}", e.getMessage());
            throw new NotificationDeliveryException(
                    "Unable to reach the notification API for login: " + e.getMessage(), e);
        }
    }

    /** Best-effort JWT exp parse; falls back to the configured token TTL. */
    private Instant deriveExpiry(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length >= 2) {
                String payloadJson = new String(
                        Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
                Object exp = parseJson(payloadJson).get("exp");
                if (exp instanceof Number n) {
                    return Instant.ofEpochSecond(n.longValue());
                }
            }
        } catch (RuntimeException ignored) {
            // Opaque token — fall through to TTL.
        }
        return clock.instant().plus(properties.tokenTtl());
    }

    private Map<String, Object> parseJson(String raw) {
        try {
            return objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new NotificationDeliveryException("Notification API returned an unparseable response", e);
        }
    }

    private void requireConfigured() {
        if (isBlank(properties.baseUrl()) || isBlank(properties.apiKey())
                || isBlank(properties.username()) || isBlank(properties.password())) {
            throw new NotificationDeliveryException(
                    "Notification API is not configured — set "
                    + "NOTIFY_API_URL/NOTIFY_API_KEY/NOTIFY_API_USERNAME/NOTIFY_API_PASSWORD");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** Internal marker for a 401 so {@link #withAuthRetryOn401} can replay once. */
    private static final class UnauthorizedException extends RuntimeException {
    }
}
