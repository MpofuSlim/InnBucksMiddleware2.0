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
                + "\"showing 20 of 143\" without walking every page. NULLABLE: the core "
                + "does not always report a total, and null means UNKNOWN, not zero. "
                + "Page until a page comes back with fewer than `limit` entries rather "
                + "than relying on this.", example = "143", nullable = true)
        Long totalCount,

        @Schema(description = "Echoed paging, so a client can page without tracking its own state.",
                example = "0")
        int offset,

        @Schema(example = "20")
        int limit
) {
}
