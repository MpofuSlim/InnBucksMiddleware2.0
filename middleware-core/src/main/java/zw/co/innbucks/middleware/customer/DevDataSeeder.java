package zw.co.innbucks.middleware.customer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import zw.co.innbucks.middleware.auth.pin.PinHasher;
import zw.co.innbucks.middleware.common.country.CountryProperties;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Slf4j
@Configuration
@Profile("dev")
public class DevDataSeeder {

    private static final String TEST_MSISDN = "+254712000001";
    private static final String TEST_PIN = "1234";
    private static final String TEST_EXTERNAL_ID = "DEV-TEST-CLIENT-001";
    private static final UUID TEST_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Bean
    ApplicationRunner seedTestCustomer(JdbcTemplate jdbcTemplate,
                                       PinHasher pinHasher,
                                       CountryProperties countryProperties) {
        return args -> {
            String country = countryProperties.country().name();
            Integer existing = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM customer WHERE country = ? AND msisdn = ?",
                    Integer.class, country, TEST_MSISDN);
            if (existing != null && existing > 0) {
                log.info("Dev seed: test customer {} already present, skipping", TEST_MSISDN);
                return;
            }

            // INSERT via JdbcTemplate: Spring Data JDBC's CrudRepository.save() with
            // a manually-assigned @Id emits UPDATE that affects 0 rows, so the row
            // would never land. Same fix we use elsewhere in the codebase.
            Instant now = Instant.now();
            jdbcTemplate.update("""
                    INSERT INTO customer
                        (id, country, msisdn, pin_hash, kyc_tier, core_provider, core_external_id,
                         status, failed_pin_attempts, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    TEST_ID,
                    country,
                    TEST_MSISDN,
                    pinHasher.hash(TEST_PIN),
                    KycTier.STANDARD.dbValue(),
                    "FINERACT",
                    TEST_EXTERNAL_ID,
                    CustomerStatus.ACTIVE.dbValue(),
                    0,
                    Timestamp.from(now),
                    Timestamp.from(now));

            log.warn("Dev seed: created test customer id={} msisdn={} pin={} coreExternalId={} (dev profile only)",
                    TEST_ID, TEST_MSISDN, TEST_PIN, TEST_EXTERNAL_ID);
        };
    }
}
