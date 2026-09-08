package zw.co.innbucks.middleware.fineract;

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
import zw.co.innbucks.middleware.corebanking.value.OperatorAccountView;
import zw.co.innbucks.middleware.corebanking.value.OperatorCredential;

/**
 * {@link CoreOperatorPort} for Fineract: one authorisation-bearing read of
 * the savings account AS THE OPERATOR, plus a best-effort client read for the
 * holder's mobile.
 *
 * <p>The {@code operatorClient} carries NO default Authorization header —
 * this middleware's own AppUser credentials must never back an operator call,
 * or the endpoint would let anyone who can reach it read any account. The
 * operator's presented header is set per request and goes nowhere but
 * Fineract, over the private cell network.
 *
 * <p>The first call is the whole security model: Fineract authenticates the
 * Basic credential and enforces READ_SAVINGSACCOUNT before answering, so a
 * 200 simultaneously proves who the operator is and that they may read this
 * account. 401 maps to {@link CoreAuthException}; 403 and 404 both map to
 * {@link CoreClientException} without distinguishing — Fineract's own
 * distinction is preserved upstream, and flattening it here avoids this
 * endpoint becoming an account-enumeration oracle cheaper than the core's.
 *
 * <p>The client read (for the holder's mobile) rides the SAME operator
 * credential and is best-effort: an operator without READ_CLIENT still gets
 * a statement, just without the phone line on it.
 */
@Slf4j
public class FineractOperatorGateway implements CoreOperatorPort {

    private final RestClient operatorClient;

    public FineractOperatorGateway(RestClient operatorClient) {
        this.operatorClient = operatorClient;
    }

    @Override
    public OperatorAccountView authorizeAndDescribeAccount(OperatorCredential credential,
                                                           String savingsAccountId) {
        SavingsAccountView account = readAsOperator(credential, savingsAccountId);
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
                account.currency().code(),
                account.accountNo(),
                blankToNull(account.clientName()),
                mobile);
    }

    private SavingsAccountView readAsOperator(OperatorCredential credential, String savingsAccountId) {
        try {
            return operatorClient.get()
                    .uri("/v1/savingsaccounts/{id}", savingsAccountId)
                    .header(HttpHeaders.AUTHORIZATION, credential.authorizationHeader())
                    .retrieve()
                    .body(SavingsAccountView.class);
        } catch (RestClientResponseException e) {
            throw mapStatus(e);
        } catch (ResourceAccessException e) {
            throw new CoreTransientException(CoreProvider.FINERACT,
                    "Fineract unreachable for operator account read", e);
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

    private RuntimeException mapStatus(RestClientResponseException e) {
        int status = e.getStatusCode().value();
        if (status == 401) {
            return new CoreAuthException(CoreProvider.FINERACT,
                    "Fineract rejected the operator credential", e);
        }
        if (status >= 400 && status < 500) {
            return new CoreClientException(CoreProvider.FINERACT,
                    "Savings account not found or not readable by this operator", e);
        }
        return new CoreServerException(CoreProvider.FINERACT,
                "Fineract failed the operator account read (HTTP " + status + ")", e);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    // Shapes per the fork's Gson-over-fields serialisation:
    // SavingsAccountData fields accountNo/externalId/clientId/clientName/currency
    // (fineract-core .../savings/data/SavingsAccountData.java:57-72), currency a
    // CurrencyData object with `code`; ClientData fields displayName/mobileNo
    // (.../client/data/ClientData.java:66-67). ExternalIdAdapter emits a bare
    // string or drops the key entirely.
    @JsonIgnoreProperties(ignoreUnknown = true)
    record SavingsAccountView(Long id, String accountNo, String externalId, Long clientId,
                              String clientName, CurrencyView currency) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        record CurrencyView(String code) {
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ClientView(String displayName, String mobileNo) {
    }
}
