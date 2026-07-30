package zw.co.innbucks.middleware.customer;

public enum KycTier {
    BASIC,
    STANDARD,
    ENHANCED;

    public String dbValue() {
        return name().toLowerCase();
    }

    public static KycTier fromDbValue(String value) {
        return valueOf(value.toUpperCase());
    }
}
