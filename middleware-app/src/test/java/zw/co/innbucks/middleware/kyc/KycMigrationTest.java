package zw.co.innbucks.middleware.kyc;

import org.junit.jupiter.api.Test;
import zw.co.innbucks.middleware.kyc.store.JdbcKycStore;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Open" is defined twice — once by the partial unique index that enforces one
 * open request per customer, once by the store's queries that find it. If the
 * two ever disagree, the app either reports no open request while the index
 * refuses a new one (the customer is stuck) or finds two. Pinned here, no
 * database needed.
 */
class KycMigrationTest {

    @Test
    void theUniqueIndexAndTheStoreAgreeOnWhatOpenMeans() throws IOException {
        String migration;
        try (InputStream in = getClass().getResourceAsStream("/db/migration/V3__kyc_intake.sql")) {
            assertThat(in).isNotNull();
            migration = normalise(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        String predicate = normalise(JdbcKycStore.OPEN_PREDICATE);
        // the index writes it without the outer parentheses
        String bare = predicate.substring(1, predicate.length() - 1);

        assertThat(migration).contains("CREATE UNIQUE INDEX uq_kyc_submission_one_open ON kyc_submission "
                + "(customer_id) WHERE " + bare + ";");
    }

    private static String normalise(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }
}
