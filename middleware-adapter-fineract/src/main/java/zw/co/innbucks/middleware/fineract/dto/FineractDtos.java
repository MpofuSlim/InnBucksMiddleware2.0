package zw.co.innbucks.middleware.fineract.dto;

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
