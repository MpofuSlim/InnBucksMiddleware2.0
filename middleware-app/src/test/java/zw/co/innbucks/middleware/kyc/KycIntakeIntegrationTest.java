package zw.co.innbucks.middleware.kyc;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import zw.co.innbucks.middleware.auth.CustomerScopes;
import zw.co.innbucks.middleware.auth.jwt.JwtIssuer;
import zw.co.innbucks.middleware.common.country.Country;
import zw.co.innbucks.middleware.customer.KycTier;
import zw.co.innbucks.middleware.customer.NationalIdHasher;
import zw.co.innbucks.middleware.kyc.crypto.KycAad;
import zw.co.innbucks.middleware.kyc.crypto.KycCipher;
import zw.co.innbucks.middleware.kyc.crypto.KycCryptoException;
import zw.co.innbucks.middleware.kyc.store.KycEvidence;
import zw.co.innbucks.middleware.kyc.store.KycFileStore;
import zw.co.innbucks.middleware.kyc.store.KycStore;
import zw.co.innbucks.middleware.kyc.store.KycSubmissionRecord;
import zw.co.innbucks.middleware.support.PostgresTestContainer;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * KYC intake against REAL Postgres, through the real security chain: the
 * partial unique index, FOR UPDATE, the evidence references and the
 * unreferenced-file deletes all live in SQL, so only a database proves them.
 * The test cell is KE, so IDs are Kenyan-shaped (synthetic).
 *
 * <p>SEPARATE CONTEXT on purpose: it switches KYC on, with a throwaway key
 * file written for this run.
 */
@SpringBootTest
@Import(PostgresTestContainer.class)
class KycIntakeIntegrationTest {

    private static final Path KEY_FILE = writeKeyFile();

    @DynamicPropertySource
    static void kyc(DynamicPropertyRegistry registry) {
        registry.add("innbucks.kyc.enabled", () -> "true");
        registry.add("innbucks.kyc.keys-file", KEY_FILE::toString);
    }

    private static Path writeKeyFile() {
        try {
            byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);
            Path file = Files.createTempFile("kyc-keys", ".it");
            Files.writeString(file, "current=v1\nv1=" + Base64.getEncoder().encodeToString(key) + "\n");
            file.toFile().deleteOnExit();
            return file;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtIssuer issuer;
    @Autowired KycStore store;
    @Autowired KycFileStore fileStore;
    @Autowired KycCipher cipher;
    @Autowired KycDecisionApplier applier;
    @Autowired KycLifecycleJob job;
    @Autowired NationalIdHasher hasher;
    @Autowired PlatformTransactionManager transactionManager;

    final ObjectMapper json = new ObjectMapper();
    MockMvc mvc;
    UUID customer;
    String bearer;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.update("TRUNCATE kyc_round_evidence, kyc_file, kyc_round, kyc_submission, idempotency_record, "
                + "ledger_transaction_event, ledger_transaction, audit_event, refresh_token, otp_challenge, "
                + "consumed_verification_token, customer CASCADE");
        customer = insertCustomer("+254712000501", null);
        bearer = bearerFor(customer);
    }

    private UUID insertCustomer(String msisdn, String signupNationalIdHash) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO customer (id, country, msisdn, pin_hash, kyc_tier, national_id_hash, core_provider,
                                      core_external_id, status, failed_pin_attempts, created_at, updated_at)
                VALUES (?, 'KE', ?, 'x', 'basic', ?, 'FINERACT', ?, 'active', 0, ?, ?)
                """, id, msisdn, signupNationalIdHash, id.toString(), Timestamp.from(now), Timestamp.from(now));
        return id;
    }

    private String bearerFor(UUID id) {
        return "Bearer " + issuer.issue(new JwtIssuer.IssueRequest(
                id.toString(), Country.KE, "basic", CustomerScopes.DEFAULT, null, null));
    }

    private UUID create(String auth, String body) throws Exception {
        MvcResult result = mvc.perform(post("/me/kyc/submissions")
                        .header(HttpHeaders.AUTHORIZATION, auth)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(json.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private static String standard(String nationalId) {
        return "{\"targetTier\":\"standard\",\"nationalId\":\"" + nationalId + "\"}";
    }

    private void upload(String auth, UUID id, String kind) throws Exception {
        mvc.perform(post("/me/kyc/submissions/" + id + "/files/" + kind)
                        .header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.IMAGE_JPEG).content(jpeg(900, 600)))
                .andExpect(status().isOk());
    }

    private void submit(String auth, UUID id) throws Exception {
        mvc.perform(post("/me/kyc/submissions/" + id + "/submit").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isAccepted());
    }

    @Test
    void fullFlow_createUploadSubmit_storesOnlyCiphertext() throws Exception {
        // another customer gave the same number at sign-up: counted, never refused
        insertCustomer("+254712000502", hasher.hash("12345678"));

        UUID id = create(bearer, standard("1234 5678"));
        upload(bearer, id, "id_front");
        mvc.perform(put("/me/kyc/submissions/" + id + "/files/id_back")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.IMAGE_JPEG).content(jpeg(900, 600)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.missing[0]").value("selfie"));
        upload(bearer, id, "selfie");
        submit(bearer, id);

        mvc.perform(get("/me/kyc").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.submission.status").value("pending_review"))
                .andExpect(jsonPath("$.onFile.idBack").value(true))
                .andExpect(jsonPath("$.onFile.nationalId").value("•".repeat(5) + "678"));

        Map<String, Object> round = jdbc.queryForMap("SELECT * FROM kyc_round WHERE submission_id = ?", id);
        assertThat(round.get("frozen_at")).isNotNull();
        assertThat(round.get("duplicate_count")).isEqualTo(1);
        assertThat(round.get("national_id_hmac")).isEqualTo(hasher.hash("12345678"));
        assertThat((String) round.get("national_id_ciphertext")).startsWith("v1:").doesNotContain("12345678");
        assertThat(cipher.openText((String) round.get("national_id_ciphertext"), KycAad.nationalId(id, 1)))
                .isEqualTo("12345678");

        List<byte[]> contents = jdbc.query("SELECT content_ciphertext FROM kyc_file WHERE submission_id = ?",
                (rs, n) -> rs.getBytes(1), id);
        assertThat(contents).hasSize(3).allSatisfy(bytes -> {
            assertThat(new String(bytes, 0, 3, StandardCharsets.US_ASCII)).isEqualTo("v1:");
            assertThat(bytes[0] & 0xFF).isNotEqualTo(0xFF);
        });
        KycEvidence selfie = store.evidence(id, 1).stream().filter(e -> e.kind() == KycFileKind.SELFIE)
                .findFirst().orElseThrow();
        byte[] plain = fileStore.read(id, selfie);
        assertThat(plain[0] & 0xFF).isEqualTo(0xFF);
        assertThat(plain[1] & 0xFF).isEqualTo(0xD8);

        // the audit trail names submissions and kinds, never the number
        List<String> audit = jdbc.queryForList(
                "SELECT action || ' ' || COALESCE(metadata::text, '') FROM audit_event WHERE customer_id = ? "
                        + "ORDER BY id", String.class, customer);
        assertThat(audit).anyMatch(a -> a.startsWith("kyc_submission_opened"))
                .anyMatch(a -> a.startsWith("kyc_file_stored"))
                .anyMatch(a -> a.startsWith("kyc_round_submitted"))
                .noneMatch(a -> a.contains("12345678") || a.contains("5678\""));
        // nor does the idempotency record
        assertThat(jdbc.queryForList("SELECT request_fingerprint || COALESCE(response_body, '') FROM idempotency_record",
                String.class)).noneMatch(r -> r.contains("12345678"));
    }

    @Test
    void oneOpenRequestPerCustomer_enforcedByThePartialUniqueIndex() throws Exception {
        String key = UUID.randomUUID().toString();
        MvcResult created = mvc.perform(post("/me/kyc/submissions").header(HttpHeaders.AUTHORIZATION, bearer)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(standard("12345678")))
                .andExpect(status().isCreated()).andReturn();
        UUID first = UUID.fromString(json.readTree(created.getResponse().getContentAsString()).get("id").asText());
        // the same key and body replays the 201, not a 409
        mvc.perform(post("/me/kyc/submissions").header(HttpHeaders.AUTHORIZATION, bearer)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(standard("1234-5678")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(first.toString()));

        mvc.perform(post("/me/kyc/submissions").header(HttpHeaders.AUTHORIZATION, bearer)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(standard("87654321")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("kyc_submission_open"))
                .andExpect(jsonPath("$.submissionId").value(first.toString()));

        // straight at the index: a second open row for the customer cannot exist
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        assertThatThrownBy(() -> store.insertSubmission(new KycSubmissionRecord(UUID.randomUUID(), customer,
                KycTier.STANDARD, KycSubmissionStatus.DRAFT, 1, null, now, null, null, now.plusSeconds(60), now)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void anotherCustomersRequestIs404() throws Exception {
        UUID id = create(bearer, standard("12345678"));
        String stranger = bearerFor(insertCustomer("+254712000503", null));

        mvc.perform(post("/me/kyc/submissions/" + id + "/files/selfie")
                        .header(HttpHeaders.AUTHORIZATION, stranger)
                        .contentType(MediaType.IMAGE_JPEG).content(jpeg(900, 600)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("kyc_submission_not_found"));
        mvc.perform(post("/me/kyc/submissions/" + id + "/submit").header(HttpHeaders.AUTHORIZATION, stranger))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kyc_file", Integer.class)).isZero();
    }

    @Test
    void theEndpointsNeedABearer() throws Exception {
        mvc.perform(get("/me/kyc")).andExpect(status().isUnauthorized());
        mvc.perform(post("/me/kyc/submissions").contentType(MediaType.APPLICATION_JSON)
                .content(standard("12345678"))).andExpect(status().isUnauthorized());
    }

    @Test
    void theDraftExpiryJobExpiresTheDraftAndDeletesItsFiles() throws Exception {
        UUID id = create(bearer, standard("12345678"));
        upload(bearer, id, "id_front");
        upload(bearer, id, "selfie");
        jdbc.update("UPDATE kyc_submission SET expires_at = now() - interval '1 minute' WHERE id = ?", id);

        job.run();

        assertThat(jdbc.queryForObject("SELECT status FROM kyc_submission WHERE id = ?", String.class, id))
                .isEqualTo("expired");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kyc_file WHERE submission_id = ?", Integer.class, id))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kyc_round_evidence WHERE submission_id = ?",
                Integer.class, id)).isZero();
        // and the customer can start again
        assertThat(create(bearer, standard("12345678"))).isNotEqualTo(id);
    }

    @Test
    void roundsInSql_redoCarriesFilesByReference_retentionSparesTheApprovedEvidence() throws Exception {
        UUID id = create(bearer, standard("12345678"));
        upload(bearer, id, "id_front");
        upload(bearer, id, "id_back");
        upload(bearer, id, "selfie");
        submit(bearer, id);
        UUID rejectedFront = fileOf(id, 1, "id_front");
        UUID back = fileOf(id, 1, "id_back");

        applier.reject(id, 1, List.of(KycRejectionReason.ID_PHOTO_UNREADABLE), Set.of(KycFileKind.ID_FRONT));

        assertThat(fileOf(id, 2, "id_back")).isEqualTo(back);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kyc_file WHERE submission_id = ?", Integer.class, id))
                .isEqualTo(3);
        // a reopened request still blocks a second one
        mvc.perform(post("/me/kyc/submissions").header(HttpHeaders.AUTHORIZATION, bearer)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(standard("12345678")))
                .andExpect(status().isConflict());
        mvc.perform(get("/me/kyc").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(jsonPath("$.submission.status").value("rejected"))
                .andExpect(jsonPath("$.submission.round").value(2))
                .andExpect(jsonPath("$.submission.rejection.redo[0]").value("id_front"))
                .andExpect(jsonPath("$.submission.missing[0]").value("id_front"));
        mvc.perform(post("/me/kyc/submissions/" + id + "/files/id_back")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.IMAGE_JPEG).content(jpeg(900, 600)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("kyc_file_not_needed"));

        upload(bearer, id, "id_front");
        submit(bearer, id);
        applier.approve(id, 2);
        List<UUID> approved = jdbc.queryForList(
                "SELECT file_id FROM kyc_round_evidence WHERE submission_id = ? AND round = 2", UUID.class, id);

        jdbc.update("UPDATE kyc_round SET decided_at = now() - interval '31 days' WHERE submission_id = ? "
                + "AND round = 1", id);
        job.run();

        assertThat(jdbc.queryForList("SELECT id FROM kyc_file WHERE submission_id = ?", UUID.class, id))
                .containsExactlyInAnyOrderElementsOf(approved)
                .doesNotContain(rejectedFront);
        assertThat(jdbc.queryForObject("SELECT files_purged_at FROM kyc_round WHERE submission_id = ? AND round = 1",
                Timestamp.class, id)).isNotNull();
    }

    @Test
    void aRoundShowsOnlyItsOwnFiles_theForeignKeyRefusesARepoint_andTheSealRefusesItWithoutTheKey()
            throws Exception {
        UUID victim = create(bearer, standard("12345678"));
        upload(bearer, victim, "id_front");
        upload(bearer, victim, "id_back");
        upload(bearer, victim, "selfie");
        submit(bearer, victim);
        UUID victimFront = fileOf(victim, 1, "id_front");
        String other = bearerFor(insertCustomer("+254712000504", null));
        UUID own = create(other, standard("23456789"));
        upload(other, own, "id_front");

        // the composite foreign key: evidence cannot point at another submission's file
        assertThatThrownBy(() -> jdbc.update("UPDATE kyc_round_evidence SET file_id = ? "
                + "WHERE submission_id = ? AND kind = 'id_front'", victimFront, own))
                .isInstanceOf(DataIntegrityViolationException.class);
        // ...nor at a file of its own submission under another kind
        UUID victimSelfie = fileOf(victim, 1, "selfie");
        assertThatThrownBy(() -> jdbc.update("UPDATE kyc_round_evidence SET file_id = ? "
                + "WHERE submission_id = ? AND round = 1 AND kind = 'id_front'", victimSelfie, victim))
                .isInstanceOf(DataIntegrityViolationException.class);

        // A writer who can also drop the constraint still gets a file that does
        // not open as the other submission's evidence. Rolled back: DDL is
        // transactional in Postgres, and the schema must stay intact.
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            jdbc.execute("ALTER TABLE kyc_round_evidence DROP CONSTRAINT fk_kyc_round_evidence_file");
            jdbc.update("UPDATE kyc_round_evidence SET file_id = ? WHERE submission_id = ? AND kind = 'id_front'",
                    victimFront, own);
            KycEvidence repointed = store.evidence(own, 1).stream()
                    .filter(e -> e.kind() == KycFileKind.ID_FRONT).findFirst().orElseThrow();
            assertThat(repointed.fileId()).isEqualTo(victimFront);
            assertThatThrownBy(() -> fileStore.read(own, repointed)).isInstanceOf(KycCryptoException.class);
            // the victim's own round still opens it
            KycEvidence genuine = store.evidence(victim, 1).stream()
                    .filter(e -> e.kind() == KycFileKind.ID_FRONT).findFirst().orElseThrow();
            assertThat(fileStore.read(victim, genuine)[0] & 0xFF).isEqualTo(0xFF);
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conname = "
                + "'fk_kyc_round_evidence_file'", Integer.class)).isEqualTo(1);
    }

    private UUID fileOf(UUID id, int round, String kind) {
        return jdbc.queryForObject("SELECT file_id FROM kyc_round_evidence WHERE submission_id = ? AND round = ? "
                + "AND kind = ?", UUID.class, id, round, kind);
    }

    private static byte[] jpeg(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        for (int y = 0; y < height; y += 20) {
            for (int x = 0; x < width; x += 20) {
                g.setColor(new Color((x * 3) % 256, (y * 7) % 256, (x + y) % 256));
                g.fillRect(x, y, 20, 20);
            }
        }
        g.dispose();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "jpeg", out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
