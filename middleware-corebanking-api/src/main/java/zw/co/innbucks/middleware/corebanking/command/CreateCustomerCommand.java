package zw.co.innbucks.middleware.corebanking.command;

import java.util.Map;
import java.util.Objects;

/**
 * Create the banking relationship for a middleware customer. Trimmed to what
 * we actually send; core-specific required fields (e.g. Fineract's officeId,
 * a tenant's custom fields) are adapter/deployment configuration, passed via
 * {@code attributes} only when the caller genuinely owns the value.
 *
 * @param requestedExternalId our customer UUID — sent when the core supports
 *        CLIENT_ASSIGNED_EXTERNAL_ID, ignored otherwise (the core assigns).
 */
public record CreateCustomerCommand(
        String requestedExternalId,
        String msisdn,
        String firstName,
        String lastName,
        Map<String, String> attributes
) {

    public CreateCustomerCommand {
        Objects.requireNonNull(msisdn, "msisdn");
        Objects.requireNonNull(firstName, "firstName");
        Objects.requireNonNull(lastName, "lastName");
        attributes = (attributes == null) ? Map.of() : Map.copyOf(attributes);
    }
}
