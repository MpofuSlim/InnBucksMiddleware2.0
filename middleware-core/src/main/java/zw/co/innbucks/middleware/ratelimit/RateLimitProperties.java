package zw.co.innbucks.middleware.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Token-bucket limits for the public auth surface and a few per-customer
 * request budgets.
 *
 * <p>Per-IP limits are enforced at the edge by {@link AuthRateLimitFilter}; the
 * per-MSISDN OTP-request limit is enforced inside {@code OtpService} (SMS-flood
 * control); the per-CUSTOMER ones (recipient lookup, step-up SMS, payment
 * requests) in their services. All are REQUEST budgets — never a source for
 * the per-tier payment limits, which come from the ledger because a bucket
 * forgets everything on restart. Buckets live in-memory (Caffeine), so the limits are <b>per
 * instance</b> — correct for the single-container-per-country deployment. If
 * this service is ever run with more than one replica, move to a shared store
 * (a Bucket4j Postgres/Redis {@code ProxyManager}) or the effective limit
 * multiplies by the replica count.
 */
@ConfigurationProperties(prefix = "innbucks.rate-limit")
public record RateLimitProperties(

        /** Master switch. Disable only for local debugging; keep on in staging/prod. */
        boolean enabled,

        /**
         * When true, the client IP is taken from the first {@code X-Forwarded-For}
         * hop instead of the socket peer. Enable ONLY behind a trusted proxy/LB
         * that sets the header — otherwise it is client-spoofable and a caller can
         * rotate it to dodge the per-IP limit.
         */
        /**
         * Read the client IP from X-Forwarded-For instead of the socket peer.
         * Required behind any proxy; UNSAFE unless the origin refuses traffic
         * that did not come through that proxy.
         */
        boolean trustForwardedFor,

        /**
         * How many proxies sit between the internet and this app (nginx alone
         * = 1, CDN + nginx = 2, ...). The client IP is taken that many entries
         * from the RIGHT of the X-Forwarded-For chain, which is the last one a
         * caller cannot forge. 0 keeps the old leftmost-entry behaviour and is
         * warned about at startup — see ClientIpResolver.
         */
        int trustedProxyCount,

        Limit ipLogin,
        Limit ipRefresh,
        Limit ipOtpRequest,
        Limit ipOtpVerify,
        Limit ipPin,
        Limit ipRegister,
        Limit msisdnOtpRequest,

        /**
         * Per-CUSTOMER budget on POST /accounts/lookup — the anti-enumeration
         * control on the phone->recipient oracle. Keyed by the authenticated
         * customer id, not IP: NAT would let one abuser drain an office's
         * shared budget, and a botnet sidesteps per-IP entirely.
         */
        Limit customerLookup,

        /**
         * Per-CUSTOMER budget on step-up approval SMS (POST /auth/step-up/request).
         * Deliberately SEPARATE from {@link #msisdnOtpRequest}: that one is
         * spendable by anyone who knows the number (public PIN_RESET requests),
         * so sharing it let a stranger exhaust it and block the owner's
         * high-value payments. This one only the signed-in customer can spend.
         * Still a cap — every approval is a billed SMS.
         */
        Limit customerStepUpRequest,

        /**
         * Per-CUSTOMER request budget on POST /transactions/* (deposit, withdraw,
         * transfer). Not a payment limit — a refused or failed attempt costs
         * nothing against those — but the bound on how fast a token (stolen or
         * buggy) can hammer the money path and the core behind it.
         */
        Limit customerPayment
) {

    /** A bucket of {@code capacity} tokens, refilled greedily over {@code refillPeriod}. */
    public record Limit(int capacity, Duration refillPeriod) {
    }
}
