package zw.co.innbucks.middleware.customer;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import zw.co.innbucks.middleware.corebanking.CoreBankingPort;
import zw.co.innbucks.middleware.corebanking.value.CoreCustomerRef;
import zw.co.innbucks.middleware.corebanking.value.CustomerProfile;

/**
 * The single way this middleware reads a customer's NAME from the core, with a
 * short-TTL cache in front of it.
 *
 * <p><b>The problem it solves.</b> Three call paths needed a name and each paid
 * a full core profile read for it: {@code GET /me/profile} (polled by the app
 * roughly once a minute per open screen), {@code POST /accounts/lookup} (once
 * per recipient the sender types), and the credit leg of every transfer alert.
 * Names are the most static thing the core holds and the cheapest thing to be
 * slightly stale about, so re-reading them at poll frequency was pure waste —
 * roughly one of the five core calls a polling client generates.
 *
 * <p><b>Scope is the safety argument.</b> This resolver returns a NAME and
 * nothing else. It is deliberately not a caching decorator around
 * {@link CoreBankingPort}: such a decorator would sit in front of balances,
 * account listings and transaction history, and no TTL makes a cached balance
 * safe. Everything this service DECIDES on is still read live — ownership from
 * the core's account list, money from the balance read, account status from the
 * local row.
 *
 * <p><b>Only successes are cached.</b> A thrown core exception and a null
 * profile both propagate uncached, so a core blip cannot pin a miss and a
 * customer whose record appears moments later is not shadowed by a negative
 * entry. The corollary is that a warm entry keeps serving through a core
 * outage for up to the TTL — deliberate, and strictly better than the
 * alternative for a display name.
 *
 * <p><b>No new information leak.</b> {@code /accounts/lookup} is an oracle
 * anyone can query by phone number, but caching does not widen it: the caller
 * still passes the live ownership check and the per-caller rate limit before a
 * name is ever rendered. The cache changes what WE pay, not what THEY learn.
 */
@Slf4j
@Service
@EnableConfigurationProperties(ProfileCacheProperties.class)
public class CustomerNameResolver {

    private final ObjectProvider<CoreBankingPort> corePort;
    private final ProfileCacheProperties properties;
    private final Counter hits;
    private final Counter misses;

    /** core externalId -> the name the core last reported for it. Successes only. */
    private final Cache<String, CustomerName> names;

    public CustomerNameResolver(ObjectProvider<CoreBankingPort> corePort,
                                ProfileCacheProperties properties,
                                MeterRegistry meterRegistry) {
        this.corePort = corePort;
        this.properties = properties;
        // expireAfterWrite, NOT expireAfterAccess: the TTL is a staleness
        // budget, so a customer whose screen is polled every minute must not
        // pin their old name indefinitely by being popular.
        this.names = Caffeine.newBuilder()
                .expireAfterWrite(properties.ttl())
                .maximumSize(properties.maximumSize())
                .build();
        this.hits = Counter.builder("innbucks.core.profile_cache")
                .description("Core profile name lookups served from cache vs fetched from the core")
                .tag("outcome", "hit")
                .register(meterRegistry);
        this.misses = Counter.builder("innbucks.core.profile_cache")
                .description("Core profile name lookups served from cache vs fetched from the core")
                .tag("outcome", "miss")
                .register(meterRegistry);
        Gauge.builder("innbucks.core.profile_cache.size", names, Cache::estimatedSize)
                .description("Customer names currently cached from the core")
                .register(meterRegistry);
    }

    @PostConstruct
    void announce() {
        if (!properties.enabled()) {
            log.info("Core profile name cache is DISABLED — every name render costs a core profile read.");
        } else {
            log.info("Core profile name cache enabled: ttl={}, maximumSize={}",
                    properties.ttl(), properties.maximumSize());
        }
    }

    /**
     * The customer's name as the core knows it.
     *
     * <p>Failure posture is unchanged from the direct call it replaces: core
     * exceptions propagate to the caller, which decides whether a missing name
     * is fatal ({@code GET /me/profile}) or merely costs the message a name
     * (the transfer alert, which catches and falls back to the account phrase).
     *
     * @param coreExternalId the customer's stable reference in the core; must not be null
     * @return the name, or null if the core has no profile for that reference
     * @throws IllegalStateException if no core adapter bean is present — the
     *         same shape of failure as calling the absent port directly, so
     *         best-effort callers that already catch RuntimeException keep
     *         behaving exactly as they did
     */
    public CustomerName resolve(String coreExternalId) {
        if (coreExternalId == null) {
            throw new IllegalArgumentException("coreExternalId must not be null");
        }
        if (properties.enabled()) {
            CustomerName cached = names.getIfPresent(coreExternalId);
            if (cached != null) {
                hits.increment();
                return cached;
            }
        }
        misses.increment();

        CustomerName fetched = fetch(coreExternalId);
        // Successes only. Caching a null would shadow a customer whose core
        // record lands moments later, for a whole TTL.
        if (properties.enabled() && fetched != null) {
            names.put(coreExternalId, fetched);
        }
        return fetched;
    }

    /**
     * Drop a cached name. Nothing calls this today, because this middleware has
     * no name-write path — the only writer is a teller or admin in the core, so
     * the TTL is the complete invalidation story. It exists for the day that
     * changes: a self-service name edit that does NOT call this would show the
     * customer their old name for a full TTL.
     */
    public void invalidate(String coreExternalId) {
        if (coreExternalId != null) {
            names.invalidate(coreExternalId);
        }
    }

    private CustomerName fetch(String coreExternalId) {
        CoreBankingPort port = corePort.getIfAvailable();
        if (port == null) {
            throw new IllegalStateException("No core banking adapter is configured");
        }
        CustomerProfile profile = port.getProfile(new CoreCustomerRef(coreExternalId));
        return profile == null ? null : new CustomerName(profile.firstName(), profile.lastName());
    }
}
