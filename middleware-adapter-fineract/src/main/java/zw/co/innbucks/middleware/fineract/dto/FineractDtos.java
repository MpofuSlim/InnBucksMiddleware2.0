package zw.co.innbucks.middleware.fineract.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;

/**
 * Deliberately TRIMMED views of Fineract responses — only the fields the
 * middleware consumes; everything else falls through as ignored JSON. Do not
 * grow these toward full-DTO mirrors.
 */
public final class FineractDtos {

    private FineractDtos() {
    }

    /**
     * The transaction-search page envelope.
     *
     * <p>Two shapes are accepted because Fineract ships both: its own legacy
     * {@code {"totalFilteredRecords": N, "pageItems": [...]}} wrapper, and the
     * Spring Data {@code {"totalElements": N, "content": [...]}} form that the
     * newer search endpoints use. Aliasing costs nothing and means a Fineract
     * upgrade that flips the envelope doesn't take the statement down.
     *
     * <p><b>{@code totalFilteredRecords} is boxed on purpose.</b> A live cell
     * returned the page with the count field ABSENT, and against a primitive
     * {@code long} Jackson refuses the whole document — so a perfectly good
     * page of transactions became a 500. Null here means "the core did not say
     * how many there are", which is a legitimate answer the caller has to be
     * able to represent rather than a parse failure.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TransactionSearchPage(
            @JsonAlias("totalElements") Long totalFilteredRecords,
            @JsonAlias("content") List<SavingsTransaction> pageItems) {
    }

    /**
     * A statement line. {@code date} arrives as a [yyyy,m,d] ARRAY from the
     * Gson-based legacy serializer, not an ISO string, so it is taken as a raw
     * Object and normalised in the adapter. Object rather than a Jackson node
     * type deliberately: these DTOs should not care which Jackson major
     * version the assembly is on.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SavingsTransaction(Long id,
                                     String externalId,
                                     TransactionTypeData transactionType,
                                     String entryType,
                                     BigDecimal amount,
                                     BigDecimal runningBalance,
                                     Boolean reversed,
                                     Object date) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TransactionTypeData(Long id, String code, String value,
                                      Boolean deposit, Boolean withdrawal) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ClientResponse(Long id, String externalId, String firstname, String lastname,
                                 Boolean active) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ClientAccountsResponse(List<SavingsSummary> savingsAccounts) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SavingsSummary(Long id, String externalId, String productName, CurrencyRef currency) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CurrencyRef(String code) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SavingsAccountResponse(Long id, String externalId, Long clientId,
                                         StatusFlags status, CurrencyRef currency,
                                         SavingsSummaryData summary) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StatusFlags(Boolean submittedAndPendingApproval, Boolean approved, Boolean active) {

        public boolean isApprovedOrActive() {
            return Boolean.TRUE.equals(approved) || Boolean.TRUE.equals(active);
        }

        public boolean isActive() {
            return Boolean.TRUE.equals(active);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SavingsSummaryData(BigDecimal accountBalance, BigDecimal availableBalance) {
    }

    /** Fineract command responses: {officeId, clientId, savingsId, resourceId, changes:{...}}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CommandResponse(Long resourceId, Long clientId, Long savingsId,
                                  java.util.Map<String, Object> changes) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SavingsTransactionResponse(Long id, BigDecimal amount, Boolean reversed) {
    }

    /** Paged list shape of GET /v1/accounttransfers. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TransferPageResponse(Integer totalFilteredRecords,
                                       List<java.util.Map<String, Object>> pageItems) {

        public boolean hasMatch() {
            return (totalFilteredRecords != null && totalFilteredRecords > 0)
                    || (pageItems != null && !pageItems.isEmpty());
        }
    }
}
