package zw.co.innbucks.middleware.customer;

public enum CustomerStatus {
    ACTIVE,
    LOCKED,
    PENDING_VERIFICATION;

    public String dbValue() {
        return name().toLowerCase();
    }

    public static CustomerStatus fromDbValue(String value) {
        return valueOf(value.toUpperCase());
    }
}
