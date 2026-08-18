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
     * <p><b>What this endpoint actually returns</b>, captured from a live cell
     * (2026-07-31) rather than assumed:
     *
     * <pre>
     * {"total":0,"content":[],"pageable":{"sort":{...},"pageNumber":0,"pageSize":3}}
     * </pre>
     *
     * So it is a Spring Data page whose count key is {@code total} — neither
     * Fineract's legacy {@code totalFilteredRecords}/{@code pageItems} wrapper
     * (which other Fineract endpoints do use) nor Spring's own default
     * {@code totalElements}. All three names are accepted: the deployed
     * Fineract version decides which one arrives, and an upgrade that flips it
     * must not take the statement down.
     *
     * <p><b>The count is boxed on purpose.</b> Before this endpoint was
     * observed, the field was a primitive {@code long} under the legacy name —
     * so the real response, which never carries that name, made Jackson reject
     * the whole document and turned a perfectly good page of transactions into
     * a 500. Null means "the core did not say how many there are": a
     * legitimate answer the caller must be able to represent, not a parse
     * failure and not zero.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TransactionSearchPage(
            @JsonAlias({"total", "totalElements"}) Long totalFilteredRecords,
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
    /**
     * One entry of {@code GET /v1/clients/external-id/{id}/accounts}.
     *
     * <p>Transcribed from the fork, not inferred: Fineract builds this from
     * {@code SavingsAccountSummaryData}, whose fields Gson serialises directly.
     * {@code accountNo}, {@code externalId}, {@code productName} and
     * {@code currency} all come from plain non-null column reads, so they are
     * safe to consume.
     *
     * <p>The payload ALSO carries {@code accountBalance}/{@code availableBalance}
     * and they are deliberately not modelled here — Fineract maps them with
     * {@code getBigDecimalDefaultToNullIfZero}, so a zero balance becomes null
     * and Gson drops the key. Money must come from the per-account read.
     */
    public record SavingsSummary(Long id, String externalId, String accountNo,
                                 String productName, CurrencyRef currency) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CurrencyRef(String code) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SavingsAccountResponse(Long id, String externalId, String accountNo, Long clientId,
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
