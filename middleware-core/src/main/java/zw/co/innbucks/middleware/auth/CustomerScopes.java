package zw.co.innbucks.middleware.auth;

import java.util.Set;

/** The scope vocabulary granted to customer access tokens. */
public final class CustomerScopes {
    private CustomerScopes() {}

    public static final String CUSTOMER_READ = "customer:read";
    public static final String CUSTOMER_WRITE = "customer:write";

    /**
     * The ONLY scope of an identity token ({@code POST /me/identity-token}):
     * it reads who the customer is ({@code GET /me/identity}) and nothing else.
     * Never granted at login or refresh, and never alongside a customer scope.
     */
    public static final String IDENTITY_READ = "identity:read";

    /** Default scopes granted to a customer on login / refresh / dev-mint. */
    public static final Set<String> DEFAULT = Set.of(CUSTOMER_READ, CUSTOMER_WRITE);
}
