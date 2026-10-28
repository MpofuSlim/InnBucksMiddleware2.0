package zw.co.innbucks.middleware.kyc;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import zw.co.innbucks.middleware.kyc.crypto.KycCipher;
import zw.co.innbucks.middleware.kyc.crypto.KycKeyRing;

import java.nio.file.Path;

/**
 * KYC intake wiring, present only with {@code innbucks.kyc.enabled=true}.
 *
 * <p>With the switch ON the key file is boot-required in EVERY profile (no key,
 * no way to seal anything): {@link KycKeyRing#load} throws and the context
 * does not start. With it OFF nothing here exists and the file is never read —
 * which is the point: a deploy to a box nobody prepared for KYC must not be
 * able to take payments down over a missing KYC key.
 */
@Slf4j
@Configuration
@ConditionalOnKycEnabled
@EnableConfigurationProperties(KycProperties.class)
public class KycConfig {

    @Bean
    public KycKeyRing kycKeyRing(KycProperties properties) {
        if (properties.keysFile().isEmpty()) {
            throw new IllegalStateException("innbucks.kyc.enabled=true but innbucks.kyc.keys-file (env "
                    + "KYC_KEYS_FILE) is not set. Point it at the read-only mounted key file (runbook: "
                    + "\"KYC encryption key\"), or switch KYC off.");
        }
        Path file = Path.of(properties.keysFile());
        KycKeyRing ring = KycKeyRing.load(file);
        KycKeyRing.permissionWarning(file).ifPresent(warning -> log.warn("KYC: {}", warning));
        log.info("KYC intake ON: key file {} holds {} key(s) {}, sealing with {}", file, ring.keyIds().size(),
                ring.keyIds(), ring.currentKeyId());
        return ring;
    }

    @Bean
    public KycCipher kycCipher(KycKeyRing kycKeyRing) {
        return new KycCipher(kycKeyRing);
    }
}
