package zw.co.innbucks.middleware.kyc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import zw.co.innbucks.middleware.auth.web.AuthExceptionHandler;
import zw.co.innbucks.middleware.config.GlobalExceptionHandler;
import zw.co.innbucks.middleware.kyc.image.TestImages;
import zw.co.innbucks.middleware.kyc.web.KycController;
import zw.co.innbucks.middleware.kyc.web.KycExceptionHandler;
import zw.co.innbucks.middleware.ratelimit.ClientIpResolver;
import zw.co.innbucks.middleware.ratelimit.RateLimitExceededException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The KYC endpoints' HTTP contract — statuses, errorCodes, headers — over the
 * real controller, advice and service (in-memory store). Standalone MockMvc:
 * no Spring context, no database; the security filter chain and the real SQL
 * are covered by KycIntakeIntegrationTest.
 */
class KycHttpTest {

    private static final String IP = "198.51.100.7";

    private final KycFixture f = new KycFixture();
    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;
    private UUID customer;

    @BeforeEach
    void setUp() {
        ClientIpResolver ipResolver = mock(ClientIpResolver.class);
        when(ipResolver.resolve(any())).thenReturn(IP);
        mvc = MockMvcBuilders.standaloneSetup(new KycController(f.service(), ipResolver))
                .setControllerAdvice(new KycExceptionHandler(), new AuthExceptionHandler(),
                        new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
        customer = f.customer("basic", null);
        signInAs(customer);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void signInAs(UUID id) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject(id.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    private String create(String body) throws Exception {
        MvcResult result = mvc.perform(post("/me/kyc/submissions")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.round").value(1))
                .andExpect(jsonPath("$.needs[0]").value("id_front"))
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private static String standard() {
        return "{\"targetTier\":\"standard\",\"nationalId\":\"63-1234567 X 18\"}";
    }

    @Test
    void theWholeFlow_overHttp() throws Exception {
        mvc.perform(get("/me/kyc"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.kycTier").value("basic"))
                .andExpect(jsonPath("$.nextTier.needs.length()").value(4))
                .andExpect(jsonPath("$.onFile.idFront").value(false))
                .andExpect(jsonPath("$.submission").doesNotExist());

        String id = create(standard());
        byte[] jpeg = TestImages.jpeg(900, 600);
        mvc.perform(post("/me/kyc/submissions/" + id + "/files/id_front")
                        .contentType(MediaType.IMAGE_JPEG).content(jpeg))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("id_front"))
                .andExpect(jsonPath("$.width").value(900))
                .andExpect(jsonPath("$.missing.length()").value(2));
        // PUT is the same handler
        mvc.perform(put("/me/kyc/submissions/" + id + "/files/id_back")
                        .contentType(MediaType.IMAGE_JPEG).content(jpeg))
                .andExpect(status().isOk());

        mvc.perform(post("/me/kyc/submissions/" + id + "/submit"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("kyc_incomplete"))
                .andExpect(jsonPath("$.missing[0]").value("selfie"));

        mvc.perform(post("/me/kyc/submissions/" + id + "/files/selfie")
                        .contentType(MediaType.IMAGE_JPEG).content(jpeg))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.missing.length()").value(0));
        mvc.perform(post("/me/kyc/submissions/" + id + "/submit"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("pending_review"))
                .andExpect(jsonPath("$.round").value(1));

        mvc.perform(get("/me/kyc"))
                .andExpect(jsonPath("$.submission.status").value("pending_review"))
                .andExpect(jsonPath("$.submission.nationalId").value("63" + "•".repeat(7) + "X18"))
                .andExpect(jsonPath("$.onFile.nationalId").value(nullValue()))
                .andExpect(jsonPath("$.onFile.selfie").value(false));

        f.applier().approve(UUID.fromString(id), 1);
        mvc.perform(get("/me/kyc"))
                .andExpect(jsonPath("$.submission.status").value("approved"))
                .andExpect(jsonPath("$.onFile.nationalId").value("63" + "•".repeat(7) + "X18"))
                .andExpect(jsonPath("$.onFile.selfie").value(true));
    }

    @Test
    void aSecondRequestIs409CarryingTheOpenId() throws Exception {
        String id = create(standard());

        mvc.perform(post("/me/kyc/submissions").header("Idempotency-Key", "k2")
                        .contentType(MediaType.APPLICATION_JSON).content(standard()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("kyc_submission_open"))
                .andExpect(jsonPath("$.submissionId").value(id))
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void createRefusalsCarryTheirErrorCodes_andNeverEchoTheNumber() throws Exception {
        MvcResult badId = mvc.perform(post("/me/kyc/submissions").header("Idempotency-Key", "k")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetTier\":\"standard\",\"nationalId\":\"63-99999 Q 77\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("national_id_invalid"))
                .andReturn();
        assertThat(badId.getResponse().getContentAsString()).doesNotContain("99999");

        mvc.perform(post("/me/kyc/submissions").header("Idempotency-Key", "k")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetTier\":\"gold\",\"nationalId\":\"63-1234567 X 18\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("kyc_target_invalid"));

        mvc.perform(post("/me/kyc/submissions").header("Idempotency-Key", "k")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetTier\":\"enhanced\",\"nationalId\":\"63-1234567 X 18\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("kyc_address_invalid"));

        mvc.perform(post("/me/kyc/submissions").contentType(MediaType.APPLICATION_JSON).content(standard()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("missing_header"));

        UUID enhanced = f.customer("enhanced", null);
        signInAs(enhanced);
        mvc.perform(post("/me/kyc/submissions").header("Idempotency-Key", "k")
                        .contentType(MediaType.APPLICATION_JSON).content(standard()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("kyc_not_available"));
    }

    @Test
    void anUnreadableBodyIsMalformedRequest() throws Exception {
        mvc.perform(post("/me/kyc/submissions").header("Idempotency-Key", "k")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetTier\":\"standard\",\"nationalId\": 63-1234567X18}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("malformed_request"));
    }

    @Test
    void uploadRefusalsCarryTheirErrorCodes() throws Exception {
        String id = create(standard());

        mvc.perform(post("/me/kyc/submissions/" + id + "/files/id_front")
                        .contentType(MediaType.IMAGE_PNG).content(TestImages.png(400, 300)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("kyc_file_invalid"))
                .andExpect(jsonPath("$.reason").value("not_jpeg"));

        // a multipart form is not decoded for the caller: it is just not a JPEG
        mvc.perform(post("/me/kyc/submissions/" + id + "/files/id_front")
                        .contentType("multipart/form-data; boundary=x")
                        .content("--x\r\nContent-Disposition: form-data; name=\"f\"\r\n\r\nabc\r\n--x--\r\n"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("not_jpeg"));

        mvc.perform(post("/me/kyc/submissions/" + id + "/files/id_front")
                        .contentType(MediaType.IMAGE_JPEG).content(new byte[2 * 1024 * 1024 + 1]))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.errorCode").value("kyc_file_too_large"))
                .andExpect(jsonPath("$.maxBytes").value(2 * 1024 * 1024));

        mvc.perform(post("/me/kyc/submissions/" + id + "/files/passport")
                        .contentType(MediaType.IMAGE_JPEG).content(TestImages.jpeg(600, 400)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("kyc_file_kind_invalid"));

        mvc.perform(post("/me/kyc/submissions/" + id + "/files/proof_of_address")
                        .contentType(MediaType.IMAGE_JPEG).content(TestImages.jpeg(600, 400)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("kyc_file_not_needed"));
    }

    @Test
    void someoneElsesOrAMalformedIdIsTheSame404() throws Exception {
        String id = create(standard());
        signInAs(f.customer("basic", null));

        for (String target : new String[]{id, UUID.randomUUID().toString(), "not-a-uuid"}) {
            mvc.perform(post("/me/kyc/submissions/" + target + "/files/selfie")
                            .contentType(MediaType.IMAGE_JPEG).content(TestImages.jpeg(600, 400)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.errorCode").value("kyc_submission_not_found"));
            mvc.perform(post("/me/kyc/submissions/" + target + "/submit"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.errorCode").value("kyc_submission_not_found"));
        }
    }

    @Test
    void rateLimitedIs429WithRetryAfter() throws Exception {
        doThrow(new RateLimitExceededException(40)).when(f.rateLimiter).upload(eq(customer), eq(IP));
        String id = create(standard());

        mvc.perform(post("/me/kyc/submissions/" + id + "/files/selfie")
                        .contentType(MediaType.IMAGE_JPEG).content(TestImages.jpeg(600, 400)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "40"))
                .andExpect(jsonPath("$.errorCode").value("rate_limited"));
    }

    @Test
    void anUnregisteredCallerIsCustomerNotRegistered() throws Exception {
        signInAs(UUID.randomUUID());
        mvc.perform(get("/me/kyc"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("customer_not_registered"));
    }

    @Test
    void theStatusExamplesHaveExactlyTheFieldsOfARealResponse() throws Exception {
        String id = create(standard());
        mvc.perform(post("/me/kyc/submissions/" + id + "/files/id_front")
                .contentType(MediaType.IMAGE_JPEG).content(TestImages.jpeg(900, 600))).andExpect(status().isOk());
        JsonNode real = json.readTree(mvc.perform(get("/me/kyc")).andReturn().getResponse().getContentAsString());

        int examples = 0;
        for (java.lang.reflect.Field field : KycController.class.getDeclaredFields()) {
            if (field.getType() == String.class && field.getName().startsWith("STATUS_")) {
                field.setAccessible(true);
                JsonNode shown = json.readTree((String) field.get(null));
                for (String path : new String[]{"", "/nextTier", "/onFile", "/submission"}) {
                    assertThat(fieldNames(shown.at(path))).as(field.getName() + " at '" + path + "'")
                            .containsExactlyElementsOf(fieldNames(real.at(path)));
                }
                examples++;
            }
        }
        assertThat(examples).isEqualTo(3);
    }

    private static java.util.List<String> fieldNames(JsonNode node) {
        java.util.List<String> names = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @Test
    void theSwaggerExamplesAreValidJson_withNoDuplicateKeys() throws Exception {
        ObjectMapper strict = json.copy().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        for (java.lang.reflect.Field field : KycController.class.getDeclaredFields()) {
            if (field.getType() == String.class && field.getName().endsWith("_EXAMPLE")) {
                field.setAccessible(true);
                JsonNode node = strict.readTree((String) field.get(null));
                assertThat(node.isObject()).as(field.getName()).isTrue();
            }
        }
    }
}
