package zw.co.innbucks.middleware.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import zw.co.innbucks.middleware.customer.NationalIdHasher;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyFingerprinterTest {

    private static final String KEY = "test-national-id-hmac-key-at-least-32-bytes-long";
    private static final String OTHER_KEY = "a-completely-different-nid-hmac-key-32+bytes!!";

    /** The shape that made this necessary: /register's body, national ID inside. */
    record Registration(String msisdn, String firstName, String lastName, String nationalId) {
    }

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final IdempotencyFingerprinter fingerprinter = new IdempotencyFingerprinter(KEY, mapper);
    private final Registration body = new Registration("+263771234567", "Tariro", "Moyo", "63-123456A42");

    @Test
    void theSameBodyAlwaysFingerprintsTheSame_inTheVersionedKeyedForm() {
        String a = fingerprinter.fingerprint(body).value();
        String b = fingerprinter.fingerprint(new Registration("+263771234567", "Tariro", "Moyo", "63-123456A42")).value();

        assertThat(a).isEqualTo(b)
                .startsWith("v2:")
                // 32-byte HMAC, base64url without padding: fits the VARCHAR(64) column.
                .hasSize(3 + 43)
                .matches("v2:[A-Za-z0-9_-]{43}");
        assertThat(fingerprinter.fingerprint(body).matches(a)).isTrue();
    }

    @Test
    void aDifferentBodyIsADifferentFingerprint_soConflictsAreStillDetected() {
        var original = fingerprinter.fingerprint(body);
        var changed = fingerprinter.fingerprint(new Registration("+263771234567", "Tariro", "Moyo", "63-123456A43"));

        assertThat(changed.value()).isNotEqualTo(original.value());
        assertThat(changed.matches(original.value())).isFalse();
    }

    @Test
    void aDifferentKeyIsADifferentFingerprint() {
        String ours = fingerprinter.fingerprint(body).value();
        String theirs = new IdempotencyFingerprinter(OTHER_KEY, mapper).fingerprint(body).value();

        assertThat(theirs).isNotEqualTo(ours);
        // A database reader without the key cannot test guesses against it.
        assertThat(fingerprinter.fingerprint(body).matches(theirs)).isFalse();
    }

    @Test
    void itIsNotTheBareHashAnyMore() throws Exception {
        byte[] canonical = mapper.writeValueAsBytes(body);
        String bareSha256 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(canonical));

        assertThat(fingerprinter.fingerprint(body).value()).isNotEqualTo("v2:" + bareSha256);
    }

    @Test
    void aRecordWrittenBeforeTheDeployStillMatchesItsOwnBody() throws Exception {
        // The pre-keyed format: SHA-256 hex of the same Jackson bytes, no prefix.
        String legacy = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(body)));

        assertThat(fingerprinter.fingerprint(body).matches(legacy)).isTrue();
        assertThat(fingerprinter.fingerprint(
                new Registration("+263771234567", "Tariro", "Moyo", "other")).matches(legacy)).isFalse();
    }

    @Test
    void nothingElseMatches() {
        var fp = fingerprinter.fingerprint(body);
        assertThat(fp.matches(null)).isFalse();
        assertThat(fp.matches("")).isFalse();
        assertThat(fp.matches(fp.value() + "x")).isFalse();
        assertThat(fp.matches(fp.value().substring(3))).as("the hash without its version").isFalse();
    }

    @Test
    void aRegistrantCannotMakeTheNationalIdHasherEmitTheSubKey() throws Exception {
        // Same parent key as NationalIdHasher, which HMACs free text (any
        // national ID up to 50 chars). Had the sub-key been HMAC(key, label),
        // registering the label as a "national ID" would store it, in hex, in
        // customer.national_id_hash. The 0xFF prefix (never in UTF-8) prevents it.
        String label = "innbucks/idempotency-fingerprint/v1";
        byte[] naiveSubKey = HexFormat.of().parseHex(new NationalIdHasher(KEY).hash(label));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(naiveSubKey, "HmacSHA256"));
        String naive = "v2:" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(mapper.writeValueAsBytes(body)));

        assertThat(fingerprinter.fingerprint(body).value()).isNotEqualTo(naive);
    }

    @Test
    void aMissingOrShortKeyFailsAtBoot() {
        assertThatThrownBy(() -> new IdempotencyFingerprinter(null, mapper)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new IdempotencyFingerprinter("", mapper)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new IdempotencyFingerprinter("too-short", mapper))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NATIONAL_ID_HMAC_KEY");
    }

    @Test
    void theKeyIsTrimmedLikeEveryOtherSecret() {
        assertThat(new IdempotencyFingerprinter("  " + KEY + "\n", mapper).fingerprint(body).value())
                .isEqualTo(fingerprinter.fingerprint(body).value());
    }
}
