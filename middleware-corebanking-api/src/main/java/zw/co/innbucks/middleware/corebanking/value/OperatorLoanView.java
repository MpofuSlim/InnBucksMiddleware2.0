package zw.co.innbucks.middleware.corebanking.value;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * What the core told an AUTHENTICATED, AUTHORISED operator about one loan —
 * the product of {@code CoreOperatorPort.authorizeAndDescribeLoan}, so its
 * existence means the core accepted the operator's credential and let that
 * operator read this loan. Header material for a loan statement; the lines
 * come from {@code listLoanTransactions}.
 *
 * <p>Everything here is DISPLAY: the statement's balances are anchored on the
 * per-transaction figures, not on this snapshot. {@code position} is the
 * core's view of what is owed NOW (not at the period end) and is rendered as
 * exactly that.
 *
 * @param principal          the approved/contracted principal (what the loan
 *                           was for); null when the core has not fixed it yet
 * @param annualInterestRate nominal annual rate in percent, e.g. 24.00; nullable
 * @param termDescription    human text such as "12 monthly repayments"; nullable
 * @param disbursedOn        actual disbursement date; null before disbursement
 * @param maturityDate       expected maturity; nullable
 * @param borrowerMobile     as the CORE stores it — display-only, may be
 *                           unnormalised or null
 * @param position           what is owed right now; null when the core cannot
 *                           say (a loan not yet disbursed, or a core that does
 *                           not summarise)
 */
public record OperatorLoanView(
        String loanId,
        String accountNumber,
        String externalId,
        String currencyCode,
        String productName,
        String status,
        boolean active,
        MinorUnits principal,
        BigDecimal annualInterestRate,
        String termDescription,
        LocalDate disbursedOn,
        LocalDate maturityDate,
        String borrowerName,
        String borrowerMobile,
        LoanPosition position
) {

    public OperatorLoanView {
        Objects.requireNonNull(loanId, "loanId");
        Objects.requireNonNull(accountNumber, "accountNumber");
        Objects.requireNonNull(currencyCode, "currencyCode");
        Objects.requireNonNull(status, "status");
    }

    /**
     * The amounts outstanding at the moment of the read, SIGNED minor units.
     * Every figure is what the core reported; none is derived here.
     *
     * @param overdueSince the date arrears began; null when nothing is overdue
     */
    public record LoanPosition(
            long principalOutstandingMinor,
            long interestOutstandingMinor,
            long feesOutstandingMinor,
            long penaltiesOutstandingMinor,
            long totalOutstandingMinor,
            long totalOverdueMinor,
            LocalDate overdueSince
    ) {
    }
}
