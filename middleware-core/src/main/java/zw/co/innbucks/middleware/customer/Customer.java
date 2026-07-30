package zw.co.innbucks.middleware.customer;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import zw.co.innbucks.middleware.common.country.Country;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table("customer")
public class Customer {

    @Id
    private UUID id;

    private String country;

    private String msisdn;

    private String pinHash;

    private String kycTier;

    private String nationalIdHash;

    /** Which core-banking system holds this customer's banking relationship (e.g. FINERACT). */
    private String coreProvider;

    /** The customer's stable reference in that core (Fineract client externalId = our customer UUID). */
    private String coreExternalId;

    private String status;

    private int failedPinAttempts;

    private Instant lastFailedPinAt;

    private Instant lockedUntil;

    private Instant createdAt;

    private Instant updatedAt;

    public Country countryEnum() {
        return Country.valueOf(country);
    }

    public CustomerStatus statusEnum() {
        return CustomerStatus.fromDbValue(status);
    }

    public KycTier kycTierEnum() {
        return KycTier.fromDbValue(kycTier);
    }
}
