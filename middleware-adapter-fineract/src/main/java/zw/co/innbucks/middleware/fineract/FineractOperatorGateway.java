package zw.co.innbucks.middleware.fineract;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import zw.co.innbucks.middleware.corebanking.CoreOperatorPort;
import zw.co.innbucks.middleware.corebanking.CoreProvider;
import zw.co.innbucks.middleware.corebanking.exception.CoreAuthException;
import zw.co.innbucks.middleware.corebanking.exception.CoreClientException;
import zw.co.innbucks.middleware.corebanking.exception.CoreServerException;
import zw.co.innbucks.middleware.corebanking.exception.CoreTransientException;
import zw.co.innbucks.middleware.corebanking.value.DepositAccountKind;
import zw.co.innbucks.middleware.corebanking.value.LoanEntryKind;
import zw.co.innbucks.middleware.corebanking.value.LoanTransactionEntry;
import zw.co.innbucks.middleware.corebanking.value.LoanTransactionPage;
import zw.co.innbucks.middleware.corebanking.value.MinorUnits;
import zw.co.innbucks.middleware.corebanking.value.OperatorAccountView;
import zw.co.innbucks.middleware.corebanking.value.OperatorCredential;
import zw.co.innbucks.middleware.corebanking.value.OperatorLoanView;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * {@link CoreOperatorPort} for Fineract: authorisation-bearing reads AS THE
 * OPERATOR — a savings/deposit account, a loan, a loan's transactions — plus
 * a best-effort client read for the holder's mobile.
 *
 * <p>The {@code operatorClient} carries NO default Authorization header —
 * this middleware's own AppUser credentials must never back an operator call,
 * or the endpoint would let anyone who can reach it read any account. The
 * operator's presented header is set per request and goes nowhere but
 * Fineract, over the private cell network.
 *
 * <p>The first call is the whole security model: Fineract authenticates the
 * Basic credential and enforces READ_SAVINGSACCOUNT / READ_LOAN before
 * answering, so a 200 simultaneously proves who the operator is and that
 * they may read this account. 401 maps to {@link CoreAuthException}; 403 and
 * 404 both map to {@link CoreClientException} without distinguishing —
 * Fineract's own distinction is preserved upstream, and flattening it here
 * avoids this endpoint becoming an account-enumeration oracle cheaper than
 * the core's.
 *
 * <p>The client read (for the holder's mobile) rides the SAME operator
 * credential and is best-effort: an operator without READ_CLIENT still gets
 * a statement, just without the phone line on it.
 *
 * <p><b>Two JSON writers, read from the fork.</b> {@code GET /v1/loans/{id}}
 * returns a String built by the legacy Gson serializer (fields, dates as
 * {@code [y,m,d]}, {@code ExternalIdAdapter} bare string or dropped key),
 * while {@code GET /v1/loans/{id}/transactions} and
 * {@code GET /v1/savingsaccounts/{id}} return the DTO itself and leave through
 * Jersey's Jackson writer ({@code JerseyJacksonObjectArgumentHandler}: getters,
 * {@code NON_NULL}, dates as ISO strings unless the DTO carries
 * {@code @JsonLocalDateArrayFormat} — {@code SavingsAccountData} does,
 * {@code LoanTransactionData} does not). The DTOs below accept both date
 * shapes and both page-count spellings so neither path can 500 a statement.
 */
@Slf4j
public class FineractOperatorGateway implements CoreOperatorPort {

    /**
     * Pure accrual bookkeeping is excluded from the loan statement at the
     * wire: a daily-accruing loan has hundreds of these rows, none of which
     * changes what the borrower owes, and Fineract's own UI hides them by
     * default. Spelled per {@code LoanTransactionApiConstants.TransactionType}.
     */
    static final List<String> EXCLUDED_LOAN_TRANSACTION_TYPES =
            List.of("accrual", "accrualActivity", "accrualAdjustment");

    private final RestClient operatorClient;

    public FineractOperatorGateway(RestClient operatorClient) {
        this.operatorClient = operatorClient;
    }

    @Override
    public OperatorAccountView authorizeAndDescribeAccount(OperatorCredential credential,
                                                           String savingsAccountId) {
        SavingsAccountView account = readAsOperator(credential, "/v1/savingsaccounts/{id}",
                savingsAccountId, SavingsAccountView.class, "Savings account");
        if (account == null || account.currency() == null || account.currency().code() == null
                || account.accountNo() == null) {
            // A 200 whose body lacks the fields Fineract always serialises is
            // not a savings account answer — refuse rather than render a
            // statement from a shape nobody has seen.
            throw new CoreServerException(CoreProvider.FINERACT,
                    "Savings account read returned an unrecognisable body", null);
        }
        String mobile = holderMobileBestEffort(credential, account.clientId());
        return new OperatorAccountView(
                blankToNull(account.externalId()),
                depositKind(account.depositType()),
                account.currency().code(),
                account.accountNo(),
                blankToNull(account.clientName()),
                mobile);
    }

    @Override
    public OperatorLoanView authorizeAndDescribeLoan(OperatorCredential credential, String loanId) {
        // No `associations` parameter on purpose: the resource reads it from
        // the raw query string (not the @DefaultValue("all") JAX-RS param), so
        // omitting it fetches the loan alone — no schedule, no transactions,
        // no charges. The lines come from the paged endpoint.
        LoanView loan = readAsOperator(credential, "/v1/loans/{id}", loanId, LoanView.class, "Loan");
        if (loan == null || loan.currency() == null || loan.currency().code() == null
                || loan.accountNo() == null || loan.status() == null) {
            throw new CoreServerException(CoreProvider.FINERACT,
                    "Loan read returned an unrecognisable body", null);
        }
        String currency = loan.currency().code();
        String mobile = holderMobileBestEffort(credential, loan.clientId());
        BigDecimal principalMajor = loan.principal() != null ? loan.principal() : loan.approvedPrincipal();
        LocalDate disbursedOn = loan.timeline() == null ? null
                : FineractDates.parseDateOrNull(loan.timeline().actualDisbursementDate());
        LocalDate maturity = loan.timeline() == null ? null
                : firstNonNull(FineractDates.parseDateOrNull(loan.timeline().expectedMaturityDate()),
                        FineractDates.parseDateOrNull(loan.timeline().actualMaturityDate()));
        return new OperatorLoanView(
                String.valueOf(loan.id() != null ? loan.id() : loanId),
                loan.accountNo(),
                blankToNull(loan.externalId()),
                currency,
                blankToNull(loan.loanProductName()),
                loan.status().value() != null ? loan.status().value() : String.valueOf(loan.status().code()),
                Boolean.TRUE.equals(loan.status().active()),
                principalMajor == null ? null : MinorUnits.ofMajor(principalMajor, currency),
                loan.annualInterestRate(),
                termDescription(loan),
                disbursedOn,
                maturity,
                blankToNull(loan.clientName()),
                mobile,
                // A position only means something once money has moved: an
                // undisbursed loan's summary is a row of zeros, and "nothing
                // outstanding" would be the wrong thing to print on it.
                disbursedOn == null ? null : position(loan.summary(), currency));
    }

    @Override
    public LoanTransactionPage listLoanTransactions(OperatorCredential credential, String loanId,
                                                    int page, int pageSize) {
        LoanTransactionsPage body;
        try {
            body = operatorClient.get()
                    .uri(uriBuilder -> {
                        uriBuilder.path("/v1/loans/{id}/transactions")
                                .queryParam("page", page)
                                .queryParam("size", pageSize)
                                // Newest first; id breaks same-day ties so page
                                // boundaries are stable between requests. These
                                // are the JPA entity's field names (dateOf, id) —
                                // the endpoint sorts via Spring Data, not SQL.
                                .queryParam("sort", "dateOf,desc")
                                .queryParam("sort", "id,desc");
                        for (String excluded : EXCLUDED_LOAN_TRANSACTION_TYPES) {
                            uriBuilder.queryParam("excludedTypes", excluded);
                        }
                        return uriBuilder.build(loanId);
                    })
                    .header(HttpHeaders.AUTHORIZATION, credential.authorizationHeader())
                    .retrieve()
                    .body(LoanTransactionsPage.class);
        } catch (RestClientResponseException e) {
            throw mapStatus(e, "Loan");
        } catch (ResourceAccessException e) {
            throw new CoreTransientException(CoreProvider.FINERACT,
                    "Fineract unreachable for operator loan transactions read", e);
        }
        if (body == null || body.content() == null) {
            return new LoanTransactionPage(List.of(), 0L);
        }
        List<LoanTransactionEntry> entries = body.content().stream().map(this::toEntry).toList();
        return new LoanTransactionPage(entries, body.totalElements());
    }

    private <T> T readAsOperator(OperatorCredential credential, String pathTemplate, String id,
                                 Class<T> type, String what) {
        try {
            return operatorClient.get()
                    .uri(pathTemplate, id)
                    .header(HttpHeaders.AUTHORIZATION, credential.authorizationHeader())
                    .retrieve()
                    .body(type);
        } catch (RestClientResponseException e) {
            throw mapStatus(e, what);
        } catch (ResourceAccessException e) {
            throw new CoreTransientException(CoreProvider.FINERACT,
                    "Fineract unreachable for operator " + what.toLowerCase(Locale.ROOT) + " read", e);
        }
    }

    private String holderMobileBestEffort(OperatorCredential credential, Long clientId) {
        if (clientId == null) {
            return null;
        }
        try {
            ClientView client = operatorClient.get()
                    .uri("/v1/clients/{id}", clientId)
                    .header(HttpHeaders.AUTHORIZATION, credential.authorizationHeader())
                    .retrieve()
                    .body(ClientView.class);
            return client == null ? null : blankToNull(client.mobileNo());
        } catch (RuntimeException e) {
            // Missing READ_CLIENT, a blip, anything: the statement still
            // renders, it just loses the phone line. Never the reason to fail.
            log.debug("Operator statement renders without holder mobile: client read failed: {}",
                    e.getMessage());
            return null;
        }
    }

    private RuntimeException mapStatus(RestClientResponseException e, String what) {
        int status = e.getStatusCode().value();
        if (status == 401) {
            return new CoreAuthException(CoreProvider.FINERACT,
                    "Fineract rejected the operator credential", e);
        }
        if (status >= 400 && status < 500) {
            return new CoreClientException(CoreProvider.FINERACT,
                    what + " not found or not readable by this operator", e);
        }
        return new CoreServerException(CoreProvider.FINERACT,
                "Fineract failed the operator " + what.toLowerCase(Locale.ROOT)
                        + " read (HTTP " + status + ")", e);
    }

    /**
     * {@code DepositAccountType}: 100 savings, 200 fixed, 300 recurring, 400
     * current (fork: {@code SavingsEnumerations.depositType}). Matched on the
     * numeric id — the code strings are stable too, but the id is what the
     * enum is keyed by upstream. An unknown or absent type is OTHER, never a
     * refusal: the account resolved and its transactions are readable, the
     * document merely gets a generic title.
     */
    private static DepositAccountKind depositKind(EnumView depositType) {
        if (depositType == null || depositType.id() == null) {
            return DepositAccountKind.OTHER;
        }
        return switch (depositType.id().intValue()) {
            case 100 -> DepositAccountKind.SAVINGS;
            case 200 -> DepositAccountKind.FIXED_DEPOSIT;
            case 300 -> DepositAccountKind.RECURRING_DEPOSIT;
            default -> DepositAccountKind.OTHER;
        };
    }

    /** "12 monthly repayments" / "24 repayments every 2 weeks"; null when the core has no term. */
    private static String termDescription(LoanView loan) {
        if (loan.numberOfRepayments() == null) {
            return null;
        }
        String unit = loan.repaymentFrequencyType() == null ? null : loan.repaymentFrequencyType().value();
        int every = loan.repaymentEvery() == null ? 1 : loan.repaymentEvery();
        if (unit == null) {
            return loan.numberOfRepayments() + " repayments";
        }
        if (every == 1) {
            String adjective = switch (unit.toLowerCase(Locale.ROOT)) {
                case "days" -> "daily";
                case "weeks" -> "weekly";
                case "months" -> "monthly";
                case "years" -> "yearly";
                default -> unit.toLowerCase(Locale.ROOT);
            };
            return loan.numberOfRepayments() + " " + adjective + " repayments";
        }
        return loan.numberOfRepayments() + " repayments every " + every + " " + unit.toLowerCase(Locale.ROOT);
    }

    /**
     * Fineract's {@code LoanSummaryData} is built with
     * {@code getBigDecimalDefaultToZeroIfNull} for every money column, so an
     * absent key here is a true zero, not an unknown — the one place that
     * default is honest.
     */
    private static OperatorLoanView.LoanPosition position(LoanSummaryView summary, String currency) {
        if (summary == null) {
            return null;
        }
        return new OperatorLoanView.LoanPosition(
                signedMinor(summary.principalOutstanding(), currency),
                signedMinor(summary.interestOutstanding(), currency),
                signedMinor(summary.feeChargesOutstanding(), currency),
                signedMinor(summary.penaltyChargesOutstanding(), currency),
                signedMinor(summary.totalOutstanding(), currency),
                signedMinor(summary.totalOverdue(), currency),
                FineractDates.parseDateOrNull(summary.overdueSinceDate()));
    }

    private LoanTransactionEntry toEntry(LoanTransactionView t) {
        if (t.currency() == null || t.currency().code() == null) {
            throw new CoreServerException(CoreProvider.FINERACT,
                    "Loan transaction " + t.id() + " carries no currency", null);
        }
        String currency = t.currency().code();
        BigDecimal amount = t.amount() == null ? BigDecimal.ZERO : t.amount();
        // A reversed row: Fineract's resetDerivedComponents() nulls the
        // portions AND the outstanding balance on reversal, and
        // manually_adjusted_or_reversed is only ever set alongside reverse().
        // reversedOnDate is the belt to that brace for rows reversed before the
        // flag existed. Either signal means "moved nothing".
        boolean reversed = Boolean.TRUE.equals(t.manuallyReversed()) || t.reversedOnDate() != null;
        Classification c = classify(t, currency);
        return new LoanTransactionEntry(
                String.valueOf(t.id()),
                blankToNull(t.externalId()),
                FineractDates.parseDate(t.date()),
                narrative(t.type()),
                c.kind(),
                MinorUnits.ofMajor(amount, currency),
                optionalMinor(t.principalPortion(), currency),
                optionalMinor(t.interestPortion(), currency),
                optionalMinor(t.feeChargesPortion(), currency),
                optionalMinor(t.penaltyChargesPortion(), currency),
                reversed ? 0 : c.deltaMinor(),
                reversed || t.outstandingLoanBalance() == null ? null
                        : signedMinor(t.outstandingLoanBalance(), currency),
                reversed);
    }

    private record Classification(LoanEntryKind kind, long deltaMinor) {
    }

    /**
     * The fork's {@code LoanTransactionType} catalogue (46 values) folded onto
     * the port's six kinds, with the principal effect each has. Keyed on the
     * numeric id, which is what the enum is defined by upstream; the
     * {@code value} label is display only.
     *
     * <ul>
     *   <li>1 disbursement: principal rises by the AMOUNT (portions are not
     *       split on a disbursement row).</li>
     *   <li>Repayment-shaped (Fineract's own {@code isRepaymentType()} set —
     *       2, 21, 22, 23, 24, 26, 28 — plus 5 at-disbursement, 8 recovery,
     *       17 charge payment, 33 interest refund): principal falls by the
     *       principal PORTION only.</li>
     *   <li>Waivers 4, 9, 31: an amount forgiven, principal portion (usually
     *       nil) reduces the balance.</li>
     *   <li>6 write-off: the principal portion leaves the balance without
     *       money moving.</li>
     *   <li>Balance-raising credits back to the borrower — 16/18 refund, 20
     *       credit-balance refund, 25 chargeback — raise it by the principal
     *       portion; 35 capitalised income adds its amount, 37 its adjustment
     *       removes it.</li>
     *   <li>Everything else (0 invalid, 3 contra, 7 rescheduling marker,
     *       10/32/34 accruals if one ever slips past the filter, 12–15
     *       transfer markers, 19 income posting, 27 charge-off — a
     *       classification, the balance stays —, 29 re-age, 30 re-amortise,
     *       36/39 amortisation, 38 termination, 40–45 buy-down/discount, and
     *       any id this middleware has not met): NONE. Shown, never totalled,
     *       never walked.</li>
     * </ul>
     */
    private static Classification classify(LoanTransactionView t, String currency) {
        int id = t.type() == null || t.type().id() == null ? 0 : t.type().id().intValue();
        long amount = signedMinor(t.amount(), currency);
        long principal = signedMinor(t.principalPortion(), currency);
        return switch (id) {
            case 1 -> new Classification(LoanEntryKind.DISBURSEMENT, amount);
            case 2, 5, 8, 17, 21, 22, 23, 24, 26, 28, 33 ->
                    new Classification(LoanEntryKind.REPAYMENT, -principal);
            case 4, 9, 31 -> new Classification(LoanEntryKind.WAIVER, -principal);
            case 6 -> new Classification(LoanEntryKind.WRITE_OFF, -principal);
            case 16, 18, 20, 25 -> new Classification(LoanEntryKind.OTHER, principal);
            case 35 -> new Classification(LoanEntryKind.OTHER, amount);
            case 37 -> new Classification(LoanEntryKind.OTHER, -amount);
            default -> new Classification(LoanEntryKind.NONE, 0);
        };
    }

    private static String narrative(EnumView type) {
        if (type == null) {
            return "Transaction";
        }
        return type.value() != null ? type.value()
                : (type.code() != null ? type.code() : "Transaction");
    }

    private static MinorUnits optionalMinor(BigDecimal major, String currency) {
        if (major == null) {
            return null;
        }
        // Portions are non-negative by construction upstream; a stray
        // negative would fail MinorUnits' invariant, and should.
        return MinorUnits.ofMajor(major.abs(), currency);
    }

    /** Signed minor units for balances and positions, which CAN be negative. */
    private static long signedMinor(BigDecimal major, String currency) {
        if (major == null) {
            return 0;
        }
        boolean negative = major.signum() < 0;
        long magnitude = MinorUnits.ofMajor(major.abs(), currency).amount();
        return negative ? -magnitude : magnitude;
    }

    private static <T> T firstNonNull(T a, T b) {
        return a != null ? a : b;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    // Shapes per the fork. SavingsAccountData leaves through the Jackson
    // writer (getters: accountNo/externalId/clientId/clientName/currency/
    // depositType — fineract-core .../savings/data/SavingsAccountData.java),
    // currency a CurrencyData with `code`, depositType an EnumOptionData
    // {id, code, value}; ExternalIdJsonConverter emits a bare string or null.
    // ClientData: displayName/mobileNo (.../client/data/ClientData.java).
    @JsonIgnoreProperties(ignoreUnknown = true)
    record SavingsAccountView(Long id, String accountNo, String externalId, Long clientId,
                              String clientName, CurrencyView currency, EnumView depositType) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CurrencyView(String code) {
    }

    /** BaseEnumOptionData {id, code, value}; LoanStatusEnumData adds `active` (both writers emit it). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record EnumView(Long id, String code, String value, Boolean active) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ClientView(String displayName, String mobileNo) {
    }

    // GET /v1/loans/{id} is a Gson String: LoanAccountData FIELDS
    // (fineract-loan .../loanaccount/data/LoanAccountData.java), dates in
    // `timeline`/`summary` as [y,m,d] arrays, ExternalIdAdapter bare string
    // or dropped key.
    @JsonIgnoreProperties(ignoreUnknown = true)
    record LoanView(Long id, String accountNo, String externalId, Long clientId, String clientName,
                    String loanProductName, EnumView status, CurrencyView currency,
                    BigDecimal principal, BigDecimal approvedPrincipal, BigDecimal annualInterestRate,
                    Integer numberOfRepayments, Integer repaymentEvery, EnumView repaymentFrequencyType,
                    LoanTimelineView timeline, LoanSummaryView summary) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record LoanTimelineView(Object actualDisbursementDate, Object expectedMaturityDate,
                            Object actualMaturityDate) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record LoanSummaryView(BigDecimal principalOutstanding, BigDecimal interestOutstanding,
                           BigDecimal feeChargesOutstanding, BigDecimal penaltyChargesOutstanding,
                           BigDecimal totalOutstanding, BigDecimal totalOverdue, Object overdueSinceDate) {
    }

    // GET /v1/loans/{id}/transactions returns Spring's Page<LoanTransactionData>
    // through the JACKSON writer: PageImpl getters (`totalElements`, `content`),
    // LoanTransactionData getters, NON_NULL inclusion (absent = null), dates as
    // ISO strings (no @JsonLocalDateArrayFormat on this DTO). `total` is
    // accepted too in case the endpoint is ever routed through Gson like the
    // savings search is.
    @JsonIgnoreProperties(ignoreUnknown = true)
    record LoanTransactionsPage(@JsonAlias({"totalElements", "total", "totalFilteredRecords"}) Long totalElements,
                                @JsonAlias({"content", "pageItems"}) List<LoanTransactionView> content) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record LoanTransactionView(Long id, String externalId, EnumView type, Object date, CurrencyView currency,
                               BigDecimal amount, BigDecimal principalPortion, BigDecimal interestPortion,
                               BigDecimal feeChargesPortion, BigDecimal penaltyChargesPortion,
                               BigDecimal outstandingLoanBalance, Boolean manuallyReversed,
                               Object reversedOnDate) {
    }
}
