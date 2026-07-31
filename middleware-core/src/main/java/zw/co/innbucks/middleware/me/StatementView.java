package zw.co.innbucks.middleware.me;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "A page of statement entries, newest first.")
public record StatementView(

        @Schema(description = "The account this statement covers.",
                example = "3f0d1c2e-8a4b-4b6e-9f1d-2c3b4a5d6e7f:wallet")
        String accountId,

        @Schema(example = "USD")
        String currency,

        List<StatementEntryView> entries,

        @Schema(description = "Total entries matching the filter, ignoring paging — for "
                + "\"showing 20 of 143\" without walking every page.", example = "143")
        long totalCount,

        @Schema(description = "Echoed paging, so a client can page without tracking its own state.",
                example = "0")
        int offset,

        @Schema(example = "20")
        int limit
) {
}
