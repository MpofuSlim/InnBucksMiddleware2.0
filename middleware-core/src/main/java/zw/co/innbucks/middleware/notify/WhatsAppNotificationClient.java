package zw.co.innbucks.middleware.notify;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import zw.co.innbucks.middleware.common.msisdn.MsisdnMasking;

import java.util.Map;

/**
 * Sends free-text messages through the external WhatsApp notification
 * gateway's {@code POST /api/messages/custom-notification} endpoint (single
 * message, max 1600 chars, authenticated with an {@code x-api-key} header) —
 * the same gateway the ticketing fleet delivers through; the wire shape is
 * ported from its proven client.
 *
 * <p>Here it is the FALLBACK channel: {@link NotificationGatewaySmsSender}
 * tries it when the SMS gateway rejects a send or is unreachable, so a
 * provider outage doesn't strand customers at the OTP step. Reaching the
 * customer over WhatsApp needs the same phone possession an SMS does, so the
 * OTP's security posture is unchanged.
 *
 * <p>Never logs the message body (it carries OTP codes) or the raw MSISDN.
 * Failures surface as {@link NotificationDeliveryException}.
 */
@Slf4j
public class WhatsAppNotificationClient {

    static final String CUSTOM_NOTIFICATION_PATH = "/api/messages/custom-notification";
    static final String API_KEY_HEADER = "x-api-key";
    static final int MAX_MESSAGE_LENGTH = 1600;

    private final RestClient restClient;
    private final NotifyProperties.Whatsapp properties;

    public WhatsAppNotificationClient(RestClient restClient, NotifyProperties.Whatsapp properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    public void sendCustomNotification(String to, String notification) {
        if (to == null || to.isBlank()) {
            throw new NotificationDeliveryException("Recipient phone number is blank");
        }
        if (notification == null || notification.isBlank()) {
            throw new NotificationDeliveryException("Notification message is blank");
        }
        if (notification.length() > MAX_MESSAGE_LENGTH) {
            throw new NotificationDeliveryException(
                    "Notification exceeds the gateway's " + MAX_MESSAGE_LENGTH + "-character limit");
        }
        try {
            restClient.post()
                    .uri(CUSTOM_NOTIFICATION_PATH)
                    .header(API_KEY_HEADER, properties.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("to", to, "notification", notification))
                    .retrieve()
                    .toBodilessEntity();
            log.info("WhatsApp notification sent to={}", MsisdnMasking.mask(to));
        } catch (RestClientResponseException ex) {
            log.warn("WhatsApp gateway rejected notification to={} status={} body={}",
                    MsisdnMasking.mask(to), ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new NotificationDeliveryException(
                    "WhatsApp gateway rejected the message: HTTP " + ex.getStatusCode().value(), ex);
        } catch (RuntimeException ex) {
            log.warn("WhatsApp gateway unreachable to={} message={}",
                    MsisdnMasking.mask(to), ex.getMessage());
            throw new NotificationDeliveryException(
                    "WhatsApp gateway is unreachable: " + ex.getMessage(), ex);
        }
    }
}
