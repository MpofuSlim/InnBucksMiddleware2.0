package zw.co.innbucks.middleware.audit;

public enum AuditOutcome {
    SUCCESS,
    FAILURE;

    public String dbValue() {
        return name().toLowerCase();
    }
}
