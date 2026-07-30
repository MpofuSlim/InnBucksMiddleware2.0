package zw.co.innbucks.middleware.notify;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import zw.co.innbucks.middleware.common.msisdn.MsisdnMasking;
import zw.co.innbucks.middleware.otp.SmsSender;

/**
 * The real {@link SmsSender}: delivers via the InnBucks notification API,
 * with an optional WhatsApp fallback — when the SMS gateway rejects the send
 * or is unreachable AND the WhatsApp gateway is configured, the same message
 * goes out through {@link WhatsAppNotificationClient} instead (same phone
 * possession, so the OTP's security posture is unchanged). Delivery fails
 * only when every configured channel fails; on the OTP path that rolls back
 * the challenge row and surfaces a retryable 503.
 *
 * <p>Metric the go-live gate watches: {@code innbucks.sms.sent} with
 * {@code outcome=success|whatsapp_fallback|failure} — alert on failures, and
 * treat a rising fallback rate as an SMS-provider incident even while
 * customers are still being reached.
 */
@Slf4j
public class NotificationGatewaySmsSender implements SmsSender {

    private final NotificationGatewayClient client;
    private final WhatsAppNotificationClient whatsAppFallback;
    private final Counter sent;
    private final Counter sentViaWhatsApp;
    private final Counter failed;

    /**
     * @param whatsAppFallback nullable — no fallback channel configured means
     *                         an SMS failure is a delivery failure.
     */
    public NotificationGatewaySmsSender(NotificationGatewayClient client,
                                        WhatsAppNotificationClient whatsAppFallback,
                                        MeterRegistry meterRegistry) {
        this.client = client;
        this.whatsAppFallback = whatsAppFallback;
        this.sent = meterRegistry.counter("innbucks.sms.sent", "outcome", "success");
        this.sentViaWhatsApp = meterRegistry.counter("innbucks.sms.sent", "outcome", "whatsapp_fallback");
        this.failed = meterRegistry.counter("innbucks.sms.sent", "outcome", "failure");
    }

    @Override
    public void send(String e164Msisdn, String body) {
        try {
            // Blank reference -> the client stamps IBMW-SMS-<uuid> for traceability.
            client.sendSms(e164Msisdn, body, null);
            sent.increment();
            return;
        } catch (NotificationDeliveryException smsFailure) {
            if (whatsAppFallback == null) {
                failed.increment();
                log.warn("SMS delivery failed to {} (no WhatsApp fallback configured): {}",
                        MsisdnMasking.mask(e164Msisdn), smsFailure.getMessage());
                throw smsFailure;
            }
            log.warn("SMS delivery failed to {} — falling back to WhatsApp: {}",
                    MsisdnMasking.mask(e164Msisdn), smsFailure.getMessage());
            try {
                // The ORIGINAL body, not the GSM-sanitised form — WhatsApp
                // renders Unicode fine; sanitisation is an SMS-gateway rule.
                whatsAppFallback.sendCustomNotification(e164Msisdn, body);
                sentViaWhatsApp.increment();
            } catch (NotificationDeliveryException whatsAppFailure) {
                failed.increment();
                log.warn("WhatsApp fallback ALSO failed to {}: {}",
                        MsisdnMasking.mask(e164Msisdn), whatsAppFailure.getMessage());
                // Surface the primary-channel failure; the fallback failure is
                // in the logs and rides along as the cause chain's suppressed leg.
                smsFailure.addSuppressed(whatsAppFailure);
                throw smsFailure;
            }
        }
    }
}
