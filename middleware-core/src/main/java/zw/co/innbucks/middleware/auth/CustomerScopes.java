package zw.co.innbucks.middleware.auth;

import java.util.Set;

/** The scope vocabulary granted to customer access tokens. */
public final class CustomerScopes {
    private CustomerScopes() {}

    public static final String CUSTOMER_READ = "customer:read";
    public static final String CUSTOMER_WRITE = "customer:write";

    /** Default scopes granted to a customer on login / refresh / dev-mint. */
    public static final Set<String> DEFAULT = Set.of(CUSTOMER_READ, CUSTOMER_WRITE);
}
