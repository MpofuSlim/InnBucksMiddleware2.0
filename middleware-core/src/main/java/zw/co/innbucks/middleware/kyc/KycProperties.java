package zw.co.innbucks.middleware.kyc;

import org.springframework.boot.context.properties.ConfigurationProperties;
import zw.co.innbucks.middleware.ratelimit.RateLimitProperties.Limit;

import java.time.Duration;

/**
 * KYC intake ({@code innbucks.kyc.*}). Bound only while the feature is
 * switched on ({@link ConditionalOnKycEnabled}); with it off none of this is
 * read, so a box whose {@code .env} predates the feature boots unchanged.
 *
 * <p>Every unset value normalises to a working default, so a context that
 * binds nothing gets the documented behaviour, never a zero cap that refuses
 * every upload.
 */
@ConfigurationProperties(prefix = "innbucks.kyc")
public record KycProperties(

        /** The switch ({@code KYC_ENABLED}). Off: every /me/kyc* path is a plain 404. */
        boolean enabled,

        /**
         * Path of the key FILE ({@code KYC_KEYS_FILE}), as the CONTAINER sees it.
         * Deliberately not an env value holding the key: the root {@code .env}
         * is copied into every cell backup, beside the ciphertext. See
         * {@code KycKeyRing} for the format.
         */
        String keysFile,

        /** How long a draft (or a round reopened by a rejection) may stay unsubmitted. */
        Duration draftTtl,

        /**
         * How long the files of a REJECTED round are kept after the decision.
         * Compliance's call — the default is a placeholder pending their
         * retention schedule. Approved-round files are never deleted here.
         */
        Duration rejectedFileRetention,

        /** When the lifecycle job runs (cron, server time = UTC). */
        String lifecycleCron,

        /** Longest typed address accepted (enhanced only). */
        int addressMaxLength,

        Image image,

        RateLimits rateLimit
) {

    public KycProperties {
        keysFile = keysFile == null ? "" : keysFile.trim();
        draftTtl = positiveOr(draftTtl, Duration.ofDays(14));
        rejectedFileRetention = positiveOr(rejectedFileRetention, Duration.ofDays(30));
        lifecycleCron = lifecycleCron == null || lifecycleCron.isBlank() ? "0 25 3 * * *" : lifecycleCron;
        addressMaxLength = addressMaxLength <= 0 ? 500 : addressMaxLength;
        image = image == null ? Image.DEFAULTS : image;
        rateLimit = rateLimit == null ? RateLimits.DEFAULTS : rateLimit;
    }

    /**
     * Upload ceilings. The byte cap is checked BEFORE the body is read; the
     * pixel cap BEFORE anything is decoded. Decodes run under a small
     * semaphore: the cell is one replica in a 768 MB container that also
     * moves money.
     */
    public record Image(
            /** Largest accepted body. nginx's client_max_body_size on the upload location sits just above it. */
            int maxBytes,
            /** Largest declared width x height accepted (checked from the header). */
            long maxPixels,
            /** Smallest accepted shorter side: below it nothing on an ID card is legible. */
            int minSide,
            /** Documents (ID front/back, proof of address) are scaled down to this longest side. */
            int documentMaxSide,
            /** Selfies are scaled down to this longest side. */
            int selfieMaxSide,
            /** Re-encode quality, 0..1. ImageIO's default 0.75 blurs the small print on an ID. */
            float jpegQuality,
            /** Decodes allowed at once, cell-wide. */
            int maxConcurrentDecodes,
            /** How long an upload waits for a decode slot before 503 kyc_upload_busy. */
            Duration decodeWait
    ) {
        static final Image DEFAULTS = new Image(0, 0, 0, 0, 0, 0f, 0, null);

        public Image {
            maxBytes = maxBytes <= 0 ? 2 * 1024 * 1024 : maxBytes;
            maxPixels = maxPixels <= 0 ? 16_000_000L : maxPixels;
            minSide = minSide <= 0 ? 200 : minSide;
            documentMaxSide = documentMaxSide <= 0 ? 1600 : documentMaxSide;
            selfieMaxSide = selfieMaxSide <= 0 ? 1080 : selfieMaxSide;
            jpegQuality = jpegQuality <= 0f || jpegQuality > 1f ? 0.9f : jpegQuality;
            maxConcurrentDecodes = maxConcurrentDecodes <= 0 ? 2 : maxConcurrentDecodes;
            decodeWait = positiveOr(decodeWait, Duration.ofSeconds(5));
        }
    }

    /**
     * Per-customer AND per-address budgets on the three writes. Spent through
     * {@code RateLimiterService}, so {@code innbucks.rate-limit.enabled} is
     * still the master switch. The per-address half is only as good as the
     * client-IP resolution (see {@code ClientIpResolver}); the per-customer
     * half is the real control.
     */
    public record RateLimits(
            Pair create,
            Pair upload,
            Pair submit
    ) {
        static final RateLimits DEFAULTS = new RateLimits(null, null, null);

        public RateLimits {
            // A new submission is rare: a handful a day covers typos.
            create = create == null ? new Pair(limit(5, Duration.ofDays(1)), limit(30, Duration.ofHours(1))) : create;
            // Four files plus retakes per round; an office NAT shares the address bucket.
            upload = upload == null ? new Pair(limit(30, Duration.ofMinutes(15)), limit(120, Duration.ofMinutes(15))) : upload;
            submit = submit == null ? new Pair(limit(10, Duration.ofMinutes(15)), limit(60, Duration.ofMinutes(15))) : submit;
        }
    }

    /** One budget per customer and one per client address. */
    public record Pair(Limit perCustomer, Limit perIp) {
        public Pair {
            perCustomer = perCustomer == null ? limit(10, Duration.ofMinutes(15)) : perCustomer;
            perIp = perIp == null ? limit(60, Duration.ofMinutes(15)) : perIp;
        }
    }

    private static Limit limit(int capacity, Duration window) {
        return new Limit(capacity, window);
    }

    private static Duration positiveOr(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }
}
