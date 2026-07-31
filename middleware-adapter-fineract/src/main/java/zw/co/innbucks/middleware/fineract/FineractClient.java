package zw.co.innbucks.middleware.fineract;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import zw.co.innbucks.middleware.corebanking.value.TxRef;
import zw.co.innbucks.middleware.fineract.dto.FineractDtos.ClientAccountsResponse;
import zw.co.innbucks.middleware.fineract.dto.FineractDtos.ClientResponse;
import zw.co.innbucks.middleware.fineract.dto.FineractDtos.CommandResponse;
import zw.co.innbucks.middleware.fineract.dto.FineractDtos.SavingsAccountResponse;
import zw.co.innbucks.middleware.fineract.dto.FineractDtos.SavingsTransactionResponse;
import zw.co.innbucks.middleware.fineract.dto.FineractDtos.TransactionSearchPage;
import zw.co.innbucks.middleware.fineract.dto.FineractDtos.TransferPageResponse;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The one HTTP surface to Fineract. Two {@link RestClient}s — READ calls ride
 * the read-only AppUser, WRITE calls the command-scoped one — both carrying
 * {@code Fineract-Platform-TenantId} and the correlation id. Every mutating
 * call sends the caller's {@code Idempotency-Key} (Fineract's command-source
 * dedup is the server-side backstop; never rely on its auto-generated key).
 * Mutating bodies always carry {@code locale} + {@code dateFormat} — Fineract
 * rejects date fields without them.
 *
 * <p>Failures are mapped here, per operation kind, onto the core-neutral
 * taxonomy (see {@link FineractErrorMapper}) and routed through
 * {@link FineractResilience} (reads retry; writes never).
 */
@Slf4j
public class FineractClient {

    static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final RestClient readClient;
    private final RestClient writeClient;
    private final FineractResilience resilience;
    private final FineractProperties properties;
    private final DateTimeFormatter dateFormatter;
    private final Clock clock;

    public FineractClient(RestClient readClient,
                          RestClient writeClient,
                          FineractResilience resilience,
                          FineractProperties properties,
                          Clock clock) {
        this.readClient = readClient;
        this.writeClient = writeClient;
        this.resilience = resilience;
        this.properties = properties;
        this.dateFormatter = DateTimeFormatter.ofPattern(properties.dateFormat(), Locale.ENGLISH);
        this.clock = clock;
    }

    // ------------------------------------------------------------------ reads

    /** @return the client, or null when Fineract positively reports 404 (no such externalId). */
    public ClientResponse findClientByExternalId(String externalId) {
        return read(() -> readClient.get()
                .uri("/v1/clients/external-id/{externalId}", externalId)
                .retrieve()
                .body(ClientResponse.class));
    }

    public ClientAccountsResponse listClientAccounts(String clientExternalId) {
        return read(() -> readClient.get()
                .uri("/v1/clients/external-id/{externalId}/accounts", clientExternalId)
                .retrieve()
                .body(ClientAccountsResponse.class));
    }

    /** @return the savings account, or null on a positive 404. */
    public SavingsAccountResponse findSavingsByExternalId(String accountExternalId) {
        return read(() -> readClient.get()
                .uri("/v1/savingsaccounts/external-id/{externalId}", accountExternalId)
                .retrieve()
                .body(SavingsAccountResponse.class));
    }

    /** @return the transaction, or null on a positive 404 (ref not present on that account). */
    public SavingsTransactionResponse findSavingsTransaction(String accountExternalId,
                                                             String transactionExternalId) {
        return read(() -> readClient.get()
                .uri("/v1/savingsaccounts/external-id/{acct}/transactions/external-id/{txn}",
                        accountExternalId, transactionExternalId)
                .retrieve()
                .body(SavingsTransactionResponse.class));
    }

    /**
     * One page of an account's transactions, newest first.
     *
     * <p>Ordered by {@code transactionDate} DESC then {@code id} DESC: value
     * date alone is ambiguous for same-day entries, and a statement whose page
     * boundaries shift between requests would double-show or skip lines.
     * {@code locale} and {@code dateFormat} are required for the date filters
     * to parse at all.
     */
    public TransactionSearchPage searchSavingsTransactions(String accountExternalId,
                                                           LocalDate from, LocalDate to,
                                                           int offset, int limit) {
        return read(() -> readClient.get()
                .uri(uri -> {
                    uri.path("/v1/savingsaccounts/external-id/{acct}/transactions/search")
                            .queryParam("offset", offset)
                            .queryParam("limit", limit)
                            .queryParam("orderBy", "transactionDate,id")
                            .queryParam("sortOrder", "DESC")
                            .queryParam("locale", properties.locale())
                            .queryParam("dateFormat", properties.dateFormat());
                    if (from != null) {
                        uri.queryParam("fromDate", from.format(dateFormatter));
                    }
                    if (to != null) {
                        uri.queryParam("toDate", to.format(dateFormatter));
                    }
                    return uri.build(accountExternalId);
                })
                .retrieve()
                .body(TransactionSearchPage.class));
    }

    public TransferPageResponse findTransfersByExternalId(String externalId) {
        return read(() -> readClient.get()
                .uri(uri -> uri.path("/v1/accounttransfers")
                        .queryParam("externalId", externalId).build())
                .retrieve()
                .body(TransferPageResponse.class));
    }

    // ----------------------------------------------------------------- writes

    public CommandResponse createClient(String externalId, String firstname, String lastname,
                                        String mobileNo, String idempotencyKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("officeId", properties.officeId());
        body.put("firstname", firstname);
        body.put("lastname", lastname);
        body.put("externalId", externalId);
        body.put("mobileNo", mobileNo);
        body.put("legalFormId", 1);
        body.put("active", true);
        body.put("activationDate", today());
        putLocaleAndDateFormat(body);
        return write(idempotencyKey, () -> writeClient.post()
                .uri("/v1/clients")
                .header(IDEMPOTENCY_HEADER, FineractIdempotencyKey.forCore(idempotencyKey))
                .body(body)
                .retrieve()
                .body(CommandResponse.class));
    }

    public CommandResponse createSavingsAccount(Long clientId, String externalId, String idempotencyKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clientId", clientId);
        body.put("productId", properties.savingsProductId());
        body.put("externalId", externalId);
        body.put("submittedOnDate", today());
        putLocaleAndDateFormat(body);
        return write(idempotencyKey, () -> writeClient.post()
                .uri("/v1/savingsaccounts")
                .header(IDEMPOTENCY_HEADER, FineractIdempotencyKey.forCore(idempotencyKey))
                .body(body)
                .retrieve()
                .body(CommandResponse.class));
    }

    public CommandResponse approveSavingsAccount(Long savingsId, String idempotencyKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("approvedOnDate", today());
        putLocaleAndDateFormat(body);
        return write(idempotencyKey, () -> writeClient.post()
                .uri("/v1/savingsaccounts/{id}?command=approve", savingsId)
                .header(IDEMPOTENCY_HEADER, FineractIdempotencyKey.forCore(idempotencyKey))
                .body(body)
                .retrieve()
                .body(CommandResponse.class));
    }

    public CommandResponse activateSavingsAccount(Long savingsId, String idempotencyKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("activatedOnDate", today());
        putLocaleAndDateFormat(body);
        return write(idempotencyKey, () -> writeClient.post()
                .uri("/v1/savingsaccounts/{id}?command=activate", savingsId)
                .header(IDEMPOTENCY_HEADER, FineractIdempotencyKey.forCore(idempotencyKey))
                .body(body)
                .retrieve()
                .body(CommandResponse.class));
    }

    public CommandResponse deposit(String accountExternalId, BigDecimal amount,
                                   String transactionExternalId, String idempotencyKey) {
        return savingsTransaction("deposit", accountExternalId, amount,
                transactionExternalId, idempotencyKey);
    }

    public CommandResponse withdraw(String accountExternalId, BigDecimal amount,
                                    String transactionExternalId, String idempotencyKey) {
        return savingsTransaction("withdrawal", accountExternalId, amount,
                transactionExternalId, idempotencyKey);
    }

    private CommandResponse savingsTransaction(String command, String accountExternalId,
                                               BigDecimal amount, String transactionExternalId,
                                               String idempotencyKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("transactionDate", today());
        body.put("transactionAmount", amount);
        // Mandatory on savings transactions — Fineract validates it notNull()
        // with no conditional, so omitting it 400s every deposit/withdrawal.
        body.put("paymentTypeId", properties.paymentTypeId());
        // The reconciliation handle: ALWAYS attached, which is what makes a
        // later 404-by-ref a POSITIVE "this write never landed".
        body.put("externalId", transactionExternalId);
        putLocaleAndDateFormat(body);
        return write(idempotencyKey, () -> writeClient.post()
                .uri("/v1/savingsaccounts/external-id/{acct}/transactions?command={cmd}",
                        accountExternalId, command)
                .header(IDEMPOTENCY_HEADER, FineractIdempotencyKey.forCore(idempotencyKey))
                .body(body)
                .retrieve()
                .body(CommandResponse.class));
    }

    public CommandResponse transfer(Long fromClientId, Long fromAccountId,
                                    Long toClientId, Long toAccountId,
                                    BigDecimal amount, String description, String idempotencyKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fromOfficeId", properties.officeId());
        body.put("fromClientId", fromClientId);
        body.put("fromAccountType", 2); // savings
        body.put("fromAccountId", fromAccountId);
        body.put("toOfficeId", properties.officeId());
        body.put("toClientId", toClientId);
        body.put("toAccountType", 2);
        body.put("toAccountId", toAccountId);
        body.put("transferAmount", amount);
        body.put("transferDate", today());
        body.put("transferDescription", description == null ? "InnBucks transfer" : description);
        putLocaleAndDateFormat(body);
        return write(idempotencyKey, () -> writeClient.post()
                .uri("/v1/accounttransfers")
                .header(IDEMPOTENCY_HEADER, FineractIdempotencyKey.forCore(idempotencyKey))
                .body(body)
                .retrieve()
                .body(CommandResponse.class));
    }

    // ------------------------------------------------------------- plumbing

    /** Reads: 404 is a POSITIVE "not found" answer → null; everything else maps + retries. */
    private <T> T read(Supplier<T> call) {
        return resilience.read(() -> {
            try {
                return call.get();
            } catch (RestClientResponseException ex) {
                if (ex.getStatusCode().equals(HttpStatusCode.valueOf(404))) {
                    return null;
                }
                throw FineractErrorMapper.mapReadFailure(ex);
            } catch (ResourceAccessException ex) {
                throw FineractErrorMapper.mapReadIoFailure(ex);
            }
        });
    }

    private <T> T write(String idempotencyKey, Supplier<T> call) {
        TxRef ref = new TxRef(idempotencyKey);
        return resilience.write(() -> {
            try {
                return call.get();
            } catch (RestClientResponseException ex) {
                throw FineractErrorMapper.mapWriteFailure(ex, ref);
            } catch (ResourceAccessException ex) {
                throw FineractErrorMapper.mapWriteIoFailure(ex, ref);
            }
        });
    }

    private void putLocaleAndDateFormat(Map<String, Object> body) {
        // Fineract rejects any body carrying dates without these two.
        body.put("locale", properties.locale());
        body.put("dateFormat", properties.dateFormat());
    }

    private String today() {
        return LocalDate.now(clock).format(dateFormatter);
    }
}
