package zw.co.innbucks.middleware.kyc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;
import zw.co.innbucks.middleware.kyc.crypto.KycCipher;
import zw.co.innbucks.middleware.kyc.crypto.KycKeyRing;
import zw.co.innbucks.middleware.kyc.crypto.KycTestKeys;
import zw.co.innbucks.middleware.kyc.image.KycImageProcessor;
import zw.co.innbucks.middleware.kyc.nationalid.KenyaNationalIdValidator;
import zw.co.innbucks.middleware.kyc.nationalid.KycNationalIds;
import zw.co.innbucks.middleware.kyc.nationalid.NationalIdValidatorRegistry;
import zw.co.innbucks.middleware.kyc.nationalid.ZimbabweNationalIdValidator;
import zw.co.innbucks.middleware.kyc.store.JdbcKycStore;
import zw.co.innbucks.middleware.kyc.store.PostgresKycFileStore;
import zw.co.innbucks.middleware.kyc.web.KycController;
import zw.co.innbucks.middleware.kyc.web.KycExceptionHandler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The switch. Off (the default): no KYC bean exists — the routes are unmapped
 * (a plain 404) and no key is read, so an unprepared box still boots and
 * takes payments. On: the key file is boot-required.
 */
class KycSwitchTest {

    /** Every KYC class Spring would pick up. */
    private static final Class<?>[] KYC_BEANS = {
            KycConfig.class, KycService.class, KycDecisionApplier.class, KycLifecycleJob.class,
            KycHousekeeping.class, KycRateLimiter.class, KycImageProcessor.class, KycNationalIds.class,
            NationalIdValidatorRegistry.class, ZimbabweNationalIdValidator.class, KenyaNationalIdValidator.class,
            JdbcKycStore.class, PostgresKycFileStore.class, KycController.class, KycExceptionHandler.class};

    @Test
    void offByDefault_nothingExistsAndNoKeyIsNeeded() {
        new ApplicationContextRunner()
                .withUserConfiguration(KYC_BEANS)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    for (Class<?> bean : KYC_BEANS) {
                        assertThat(context).doesNotHaveBean(bean);
                    }
                    assertThat(context).doesNotHaveBean(KycKeyRing.class);
                });
        new ApplicationContextRunner()
                .withPropertyValues("innbucks.kyc.enabled=false", "innbucks.kyc.keys-file=/nowhere")
                .withUserConfiguration(KYC_BEANS)
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(KycController.class));
    }

    @Test
    void onWithoutAKeyFile_theBootIsRefused() {
        new ApplicationContextRunner()
                .withPropertyValues("innbucks.kyc.enabled=true")
                .withUserConfiguration(KycConfig.class)
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .rootCause().hasMessageContaining("KYC_KEYS_FILE"));
        new ApplicationContextRunner()
                .withPropertyValues("innbucks.kyc.enabled=true", "innbucks.kyc.keys-file=/does/not/exist")
                .withUserConfiguration(KycConfig.class)
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .rootCause().hasMessageContaining("does not exist"));
    }

    @Test
    void onWithAKeyFile_theRingAndCipherExist(@TempDir Path dir) throws IOException {
        Path keys = dir.resolve("kyc-keys");
        Files.writeString(keys, "current=v1\nv1=" + KycTestKeys.newKey() + "\n");

        new ApplicationContextRunner()
                .withPropertyValues("innbucks.kyc.enabled=true", "innbucks.kyc.keys-file=" + keys)
                .withUserConfiguration(KycConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(KycCipher.class);
                    assertThat(context.getBean(KycKeyRing.class).currentKeyId()).isEqualTo("v1");
                });
    }

    /**
     * Switched on, every KYC bean wires (constructor choice, dependencies) —
     * proven here without a database, so a wiring slip fails the unit build,
     * not only the Postgres ITs.
     */
    @Test
    void onWithAKeyFile_everyKycBeanWires(@TempDir Path dir) throws IOException {
        Path keys = dir.resolve("kyc-keys");
        Files.writeString(keys, "current=v1\nv1=" + KycTestKeys.newKey() + "\n");

        new ApplicationContextRunner()
                .withPropertyValues("innbucks.kyc.enabled=true", "innbucks.kyc.keys-file=" + keys)
                .withBean(org.springframework.jdbc.core.JdbcTemplate.class,
                        () -> org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class))
                .withBean(zw.co.innbucks.middleware.customer.NationalIdHasher.class,
                        () -> new zw.co.innbucks.middleware.customer.NationalIdHasher(
                                "test-national-id-hmac-key-at-least-32-bytes-long"))
                .withBean(zw.co.innbucks.middleware.common.country.CountryProperties.class,
                        () -> new zw.co.innbucks.middleware.common.country.CountryProperties(
                                zw.co.innbucks.middleware.common.country.Country.ZW))
                .withBean(zw.co.innbucks.middleware.ratelimit.RateLimiterService.class,
                        () -> org.mockito.Mockito.mock(zw.co.innbucks.middleware.ratelimit.RateLimiterService.class))
                .withBean(zw.co.innbucks.middleware.ratelimit.ClientIpResolver.class,
                        () -> org.mockito.Mockito.mock(zw.co.innbucks.middleware.ratelimit.ClientIpResolver.class))
                .withBean(zw.co.innbucks.middleware.customer.CustomerRepository.class,
                        () -> org.mockito.Mockito.mock(zw.co.innbucks.middleware.customer.CustomerRepository.class))
                .withBean(zw.co.innbucks.middleware.customer.KycTierResolver.class,
                        () -> c -> zw.co.innbucks.middleware.customer.KycTierResolution.unavailable())
                .withBean(zw.co.innbucks.middleware.idempotency.IdempotencyService.class,
                        () -> org.mockito.Mockito.mock(zw.co.innbucks.middleware.idempotency.IdempotencyService.class))
                .withBean(zw.co.innbucks.middleware.audit.AuditService.class,
                        () -> org.mockito.Mockito.mock(zw.co.innbucks.middleware.audit.AuditService.class))
                .withBean(org.springframework.transaction.PlatformTransactionManager.class,
                        KycFixture.NoopTransactionManager::new)
                .withBean(java.time.Clock.class, java.time.Clock::systemUTC)
                .withBean(io.micrometer.core.instrument.MeterRegistry.class,
                        io.micrometer.core.instrument.simple.SimpleMeterRegistry::new)
                .withUserConfiguration(KYC_BEANS)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    for (Class<?> bean : KYC_BEANS) {
                        assertThat(context).hasSingleBean(bean);
                    }
                });
    }

    /** A KYC bean added later must not escape the switch. */
    @Test
    void everyKycComponentCarriesTheSwitch() {
        // The scanner evaluates @Conditional itself: switch KYC on so every
        // component is a candidate, then check each carries the switch.
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false,
                new org.springframework.mock.env.MockEnvironment().withProperty("innbucks.kyc.enabled", "true"));
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(org.springframework.context.annotation.Configuration.class));
        Set<BeanDefinition> found = scanner.findCandidateComponents("zw.co.innbucks.middleware.kyc");

        assertThat(found).hasSizeGreaterThanOrEqualTo(KYC_BEANS.length);
        for (BeanDefinition definition : found) {
            Class<?> type;
            try {
                type = Class.forName(definition.getBeanClassName());
            } catch (ClassNotFoundException ex) {
                throw new AssertionError(ex);
            }
            assertThat(AnnotatedElementUtils.hasAnnotation(type, ConditionalOnKycEnabled.class))
                    .as(type.getName() + " must carry @ConditionalOnKycEnabled")
                    .isTrue();
        }
    }
}
