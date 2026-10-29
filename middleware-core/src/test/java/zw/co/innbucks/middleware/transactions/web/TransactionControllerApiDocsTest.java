package zw.co.innbucks.middleware.transactions.web;

import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The documented error answers of the three money endpoints, read off the
 * annotations springdoc publishes. Two features document these endpoints
 * side by side — the per-tier limits (422 {@code limit_exceeded}, 429
 * {@code rate_limited}) and the core refusing this service's own credentials
 * (502 {@code core_banking_unavailable}) — plus the deposit switch (403
 * {@code deposit_disabled}). Each used to own the same {@code @ApiResponse}
 * slots, so a later edit to one could silently drop the other's entry; this
 * pins that every one of them is present where it applies.
 */
class TransactionControllerApiDocsTest {

    private static Map<String, ApiResponse> responses(String endpoint) {
        Method method = Arrays.stream(TransactionController.class.getDeclaredMethods())
                .filter(m -> m.getName().equals(endpoint))
                .findFirst().orElseThrow();
        ApiResponses annotation = method.getAnnotation(ApiResponses.class);
        assertThat(annotation).as("@ApiResponses on %s", endpoint).isNotNull();
        return Arrays.stream(annotation.value())
                .collect(Collectors.toMap(ApiResponse::responseCode, Function.identity()));
    }

    private static List<String> examples(ApiResponse response) {
        return Arrays.stream(response.content())
                .flatMap(c -> Arrays.stream(c.examples()))
                .map(ExampleObject::value)
                .toList();
    }

    @Test
    void withdrawAndTransferDocumentTheLimitsTheBudgetAndTheCoreRefusingUs() {
        for (String endpoint : List.of("withdraw", "transfer")) {
            Map<String, ApiResponse> byCode = responses(endpoint);
            assertThat(byCode).as(endpoint).containsKeys("200", "401", "403", "409", "422", "429", "502", "503");

            assertThat(examples(byCode.get("422"))).as(endpoint + " 422")
                    .anySatisfy(e -> assertThat(e).contains("\"limit\":\"daily\"", "limit_exceeded"))
                    .anySatisfy(e -> assertThat(e).contains("\"limit\":\"per_payment\""))
                    .anySatisfy(e -> assertThat(e).contains("\"limit\":\"daily_count\""))
                    .anySatisfy(e -> assertThat(e).contains("core_rejected"));
            assertThat(examples(byCode.get("429"))).as(endpoint + " 429")
                    .singleElement().asString().contains("rate_limited");
            assertThat(examples(byCode.get("502"))).as(endpoint + " 502")
                    .singleElement().asString().contains("\"status\":502", "core_banking_unavailable");
            assertThat(byCode.get("502").description()).as(endpoint + " 502")
                    .contains("core_auth_failed");
            assertThat(byCode.get("503").description()).as(endpoint + " 503")
                    .contains("kyc_tier_unavailable");
        }
    }

    @Test
    void depositDocumentsTheSwitchTheBudgetAndTheCoreRefusingUsButNoLimits() {
        Map<String, ApiResponse> byCode = responses("deposit");
        assertThat(byCode).containsKeys("200", "401", "403", "409", "422", "429", "502", "503");

        assertThat(examples(byCode.get("403")))
                .anySatisfy(e -> assertThat(e).contains("deposit_disabled"))
                .anySatisfy(e -> assertThat(e).contains("account_ownership_mismatch"));
        // A switched-off deposit is refused before the budget, and says so.
        assertThat(byCode.get("429").description()).contains("deposit_disabled", "never spends it");
        assertThat(examples(byCode.get("502")))
                .singleElement().asString().contains("core_banking_unavailable");
        assertThat(byCode.get("502").description()).contains("core's own lock");
        // Deposits are not limited: no limit_exceeded is promised.
        assertThat(examples(byCode.get("422"))).noneSatisfy(e -> assertThat(e).contains("limit_exceeded"));
        assertThat(byCode.get("422").description()).contains("not subject to the per-tier payment limits");
    }
}
