package zw.co.innbucks.middleware.corebanking.value;

import java.util.Objects;

/**
 * What the core told an AUTHENTICATED, AUTHORISED operator about one deposit
 * account — the product of {@code CoreOperatorPort.authorizeAndDescribeAccount},
 * so its mere existence means the core accepted the operator's credential and
 * let that operator read this account.
 *
 * @param accountExternalId the account's external reference — the key this
 *                          middleware's transaction reads are indexed by.
 *                          NULLABLE: a branch-created account may have none,
 *                          and callers must refuse work that needs it rather
 *                          than guess
 * @param kind              savings, fixed or recurring deposit — decides the
 *                          document's title only; every kind statements the
 *                          same way
 * @param holderName        display name of the account holder; nullable
 * @param holderMobile      the holder's mobile as the CORE stores it —
 *                          display-only, may be unnormalised or null; never
 *                          feed it to anything that expects an MSISDN
 */
public record OperatorAccountView(
        String accountExternalId,
        DepositAccountKind kind,
        String currencyCode,
        String accountNumber,
        String holderName,
        String holderMobile
) {

    public OperatorAccountView {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(currencyCode, "currencyCode");
        Objects.requireNonNull(accountNumber, "accountNumber");
    }
}
