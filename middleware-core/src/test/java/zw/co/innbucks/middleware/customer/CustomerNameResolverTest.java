package zw.co.innbucks.middleware.customer;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import zw.co.innbucks.middleware.corebanking.CoreBankingPort;
import zw.co.innbucks.middleware.corebanking.CoreProvider;
import zw.co.innbucks.middleware.corebanking.exception.CoreTransientException;
import zw.co.innbucks.middleware.corebanking.value.CoreCustomerRef;
import zw.co.innbucks.middleware.corebanking.value.CustomerProfile;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the cache may and may not do. The performance win is the easy half; the
 * cases that matter are the ones that decide whether a stale or absent entry
 * can ever become a wrong answer.
 */
class CustomerNameResolverTest {

    private static final String REF = "9a8b7c6d-5e4f-4a3b-2c1d-0e9f8a7b6c5d";

    private final CoreBankingPort port = mock(CoreBankingPort.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @SuppressWarnings("unchecked")
    private CustomerNameResolver resolver(boolean enabled, Duration ttl) {
        ObjectProvider<CoreBankingPort> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(port);
        return new CustomerNameResolver(provider,
                new ProfileCacheProperties(enabled, ttl, 1000), meterRegistry);
    }

    @SuppressWarnings("unchecked")
    private CustomerNameResolver resolverWithoutAdapter() {
        ObjectProvider<CoreBankingPort> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return new CustomerNameResolver(provider,
                new ProfileCacheProperties(true, Duration.ofMinutes(5), 1000), meterRegistry);
    }

    private void coreReturns(String first, String last) {
        when(port.getProfile(new CoreCustomerRef(REF)))
                .thenReturn(new CustomerProfile(new CoreCustomerRef(REF), first, last, "active"));
    }

    @Test
    void theSecondReadIsServedWithoutTouchingTheCore() {
        coreReturns("Tariro", "Mpofu");
        CustomerNameResolver resolver = resolver(true, Duration.ofMinutes(5));

        assertThat(resolver.resolve(REF)).isEqualTo(new CustomerName("Tariro", "Mpofu"));
        assertThat(resolver.resolve(REF)).isEqualTo(new CustomerName("Tariro", "Mpofu"));
        assertThat(resolver.resolve(REF)).isEqualTo(new CustomerName("Tariro", "Mpofu"));

        verify(port, times(1)).getProfile(any());
        assertThat(counted("hit")).isEqualTo(2.0);
        assertThat(counted("miss")).isEqualTo(1.0);
    }

    @Test
    void differentCustomersNeverShareAnEntry() {
        String other = "3f0d1c2e-8a4b-4b6e-9f1d-2c3b4a5d6e7f";
        coreReturns("Tariro", "Mpofu");
        when(port.getProfile(new CoreCustomerRef(other)))
                .thenReturn(new CustomerProfile(new CoreCustomerRef(other), "Rudo", "Chikafu", "active"));
        CustomerNameResolver resolver = resolver(true, Duration.ofMinutes(5));

        assertThat(resolver.resolve(REF).firstName()).isEqualTo("Tariro");
        assertThat(resolver.resolve(other).firstName()).isEqualTo("Rudo");
        assertThat(resolver.resolve(REF).firstName()).isEqualTo("Tariro");
    }

    /**
     * A core blip must NOT be remembered. Caching a failure would shadow a
     * customer for a whole TTL over one transient error — the opposite of the
     * resilience the cache is supposed to add.
     */
    @Test
    void aFailedReadIsNeverCached() {
        when(port.getProfile(any()))
                .thenThrow(new CoreTransientException(CoreProvider.FINERACT, "core down", null))
                .thenReturn(new CustomerProfile(new CoreCustomerRef(REF), "Tariro", "Mpofu", "active"));
        CustomerNameResolver resolver = resolver(true, Duration.ofMinutes(5));

        assertThatThrownBy(() -> resolver.resolve(REF)).isInstanceOf(CoreTransientException.class);
        // The very next call goes back to the core and succeeds.
        assertThat(resolver.resolve(REF).firstName()).isEqualTo("Tariro");
        verify(port, times(2)).getProfile(any());
    }

    /**
     * Same rule for a core that returns no profile: an absent record is a
     * moment in time, not a fact to remember. A customer whose core record
     * lands seconds later must not be nameless for a full TTL.
     */
    @Test
    void anAbsentProfileIsNeverCached() {
        when(port.getProfile(any()))
                .thenReturn(null)
                .thenReturn(new CustomerProfile(new CoreCustomerRef(REF), "Tariro", "Mpofu", "active"));
        CustomerNameResolver resolver = resolver(true, Duration.ofMinutes(5));

        assertThat(resolver.resolve(REF)).isNull();
        assertThat(resolver.resolve(REF).firstName()).isEqualTo("Tariro");
        verify(port, times(2)).getProfile(any());
    }

    /** A name correction in the core reaches the app once the entry expires. */
    @Test
    void aRenameIsPickedUpAfterTheTtlExpires() throws Exception {
        coreReturns("Tariro", "Mpofu");
        CustomerNameResolver resolver = resolver(true, Duration.ofMillis(40));

        assertThat(resolver.resolve(REF).lastName()).isEqualTo("Mpofu");
        coreReturns("Tariro", "Moyo");
        // Still the old value while the entry is live — the accepted staleness.
        assertThat(resolver.resolve(REF).lastName()).isEqualTo("Mpofu");

        Thread.sleep(120);
        assertThat(resolver.resolve(REF).lastName()).isEqualTo("Moyo");
    }

    /** The invalidation hook the first name-write path will need. */
    @Test
    void invalidateForcesTheNextReadBackToTheCore() {
        coreReturns("Tariro", "Mpofu");
        CustomerNameResolver resolver = resolver(true, Duration.ofMinutes(5));

        resolver.resolve(REF);
        resolver.invalidate(REF);
        resolver.resolve(REF);

        verify(port, times(2)).getProfile(any());
    }

    /** The escape hatch: off means every read goes to the core, as before. */
    @Test
    void disabledMeansNoCachingAtAll() {
        coreReturns("Tariro", "Mpofu");
        CustomerNameResolver resolver = resolver(false, Duration.ofMinutes(5));

        resolver.resolve(REF);
        resolver.resolve(REF);
        resolver.resolve(REF);

        verify(port, times(3)).getProfile(any());
    }

    /**
     * No adapter bean is a RuntimeException, matching what calling the absent
     * port directly used to do — so best-effort callers that already catch
     * RuntimeException (the transfer alert) keep falling back exactly as they
     * did rather than propagating a new failure shape.
     */
    @Test
    void anAbsentAdapterFailsAsARuntimeExceptionAndCachesNothing() {
        CustomerNameResolver resolver = resolverWithoutAdapter();

        assertThatThrownBy(() -> resolver.resolve(REF)).isInstanceOf(IllegalStateException.class);
        verify(port, never()).getProfile(any());
    }

    /**
     * A zero or unset TTL must not silently produce a cache that expires
     * everything instantly while reporting as enabled.
     */
    @Test
    void anUnsetTtlFallsBackToTheDefaultRatherThanZero() {
        assertThat(new ProfileCacheProperties(true, null, 0).ttl()).isEqualTo(Duration.ofMinutes(5));
        assertThat(new ProfileCacheProperties(true, Duration.ZERO, 0).ttl()).isEqualTo(Duration.ofMinutes(5));
        assertThat(new ProfileCacheProperties(true, null, 0).maximumSize()).isEqualTo(50_000);
    }

    private double counted(String outcome) {
        return meterRegistry.counter("innbucks.core.profile_cache", "outcome", outcome).count();
    }
}
