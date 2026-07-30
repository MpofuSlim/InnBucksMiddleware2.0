package zw.co.innbucks.middleware.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Spring Boot 4's auto-configuration does not appear to provide a
 * FlywayMigrationInitializer out of the box for our dependency set, so the
 * customer table was never created on test Postgres containers. We run Flyway
 * explicitly during context startup against the wired DataSource — same effect
 * as the auto-config would have had, but version-independent.
 *
 * <p>{@code baselineOnMigrate} is wired from {@code spring.flyway.baseline-on-migrate}
 * so the prod profile can override the dev-friendly default. With it on, an
 * empty schema (or one that pre-existed Flyway tracking) accepts later
 * migrations as the baseline — handy in dev, dangerous in prod where a
 * runbook accident (drop + recreate schema) could silently leave the DB at
 * the wrong state. application-prod.yaml flips it to false.
 */
@Slf4j
@Configuration
public class FlywayConfig {

    private final DataSource dataSource;
    private final boolean baselineOnMigrate;

    public FlywayConfig(DataSource dataSource,
                        @Value("${spring.flyway.baseline-on-migrate:true}") boolean baselineOnMigrate) {
        this.dataSource = dataSource;
        this.baselineOnMigrate = baselineOnMigrate;
    }

    @PostConstruct
    public void migrate() {
        log.info("Running Flyway migrations against {} (baselineOnMigrate={})",
                dataSource, baselineOnMigrate);
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(baselineOnMigrate)
                .load();
        int applied = flyway.migrate().migrationsExecuted;
        log.info("Flyway applied {} migration(s)", applied);
    }
}
