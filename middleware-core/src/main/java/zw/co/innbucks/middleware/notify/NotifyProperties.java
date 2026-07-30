package zw.co.innbucks.middleware.notify;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Outbound notification wiring. {@code provider} selects the {@link
 * zw.co.innbucks.middleware.otp.SmsSender} implementation:
 *
 * <ul>
 *   <li>{@code console} — the no-network {@link
 *       zw.co.innbucks.middleware.otp.ConsoleSmsSender} stub (dev/UAT only;
 *       OTP codes are never delivered to customers).</li>
 *   <li>{@code innbucks-gateway} — the InnBucks notification API
 *       ({@link NotificationGatewayClient}): the same platform gateway the
 *       ticketing fleet sends SMS/email through. When selected, every
 *       credential field below is boot-required (fail-fast, like the
 *       Fineract cell wiring).</li>
 * </ul>
 *
 * <p>The gateway credentials are per-deployment secrets (env vars) — the
 * API key + third-party login pair are provisioned by the InnBucks platform
 * team, never committed.
 */
@Validated
@ConfigurationProperties(prefix = "innbucks.notify")
public record NotifyProperties(

        /** SmsSender selection: console | innbucks-gateway. */
        @NotBlank
        String provider,

        /** Notification API base URL, e.g. https://api.innbucks.example — no trailing slash. */
        String baseUrl,

        /** X-Api-Key header value sent on every call, login included. */
        String apiKey,

        /** Third-party login username (POST /auth/third-party). */
        String username,

        /** Third-party login password. */
        String password,

        /**
         * Fallback bearer-token lifetime, used only when the gateway's JWT
         * {@code exp} claim can't be parsed. The client normally caches the
         * token until {@code exp} minus a 30s safety margin.
         */
        @NotNull
        Duration tokenTtl,

        @NotNull
        Duration connectTimeout,

        @NotNull
        Duration readTimeout,

        /**
         * Optional WhatsApp fallback channel: when the SMS gateway rejects a
         * send or is unreachable, the same message is delivered through the
         * external WhatsApp notification gateway (the ticketing fleet's
         * gateway — {@code POST /api/messages/custom-notification},
         * {@code x-api-key} auth) so an SMS-provider outage doesn't strand
         * customers at the OTP step. Delivery still fails (and the OTP tx
         * still rolls back) only if BOTH channels fail.
         */
        @NotNull
        Whatsapp whatsapp
) {

    public static final String PROVIDER_CONSOLE = "console";
    public static final String PROVIDER_INNBUCKS_GATEWAY = "innbucks-gateway";

    public record Whatsapp(

            /** Off by default; when true, base-url + api-key are boot-required. */
            boolean enabled,

            /** WhatsApp gateway base URL, e.g. https://wa.innbucks.example — no trailing slash. */
            String baseUrl,

            /** x-api-key header value. */
            String apiKey,

            @NotNull
            Duration connectTimeout,

            @NotNull
            Duration readTimeout
    ) {
    }
}
