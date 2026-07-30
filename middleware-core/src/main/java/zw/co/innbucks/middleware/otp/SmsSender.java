package zw.co.innbucks.middleware.otp;

/**
 * SPI for outbound SMS delivery. Real-world implementations (Africa's Talking,
 * Twilio, Safaricom Bulk SMS, etc.) will plug in behind this interface; today
 * the only impl is {@link ConsoleSmsSender}, a no-network stub that logs a
 * masked breadcrumb (never the message body) until a real adapter lands.
 */
public interface SmsSender {

    void send(String e164Msisdn, String body);
}
