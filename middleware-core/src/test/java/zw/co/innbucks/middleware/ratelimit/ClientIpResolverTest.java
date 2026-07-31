package zw.co.innbucks.middleware.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Who the limiter thinks you are. The security-relevant case is the last one:
 * a caller prepending their own entry to X-Forwarded-For must NOT get a fresh
 * bucket, or the limiter is decorative for anyone who reads the header spec.
 */
class ClientIpResolverTest {

    private static final String PEER = "10.0.0.9";

    private static RateLimitProperties props(boolean trust, int hops) {
        RateLimitProperties.Limit limit = new RateLimitProperties.Limit(5, Duration.ofMinutes(1));
        return new RateLimitProperties(true, trust, hops,
                limit, limit, limit, limit, limit, limit, limit, limit);
    }

    private static HttpServletRequest request(String forwardedFor, String realIp) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn(PEER);
        when(request.getHeader(ClientIpResolver.FORWARDED_FOR)).thenReturn(forwardedFor);
        when(request.getHeader(ClientIpResolver.REAL_IP)).thenReturn(realIp);
        return request;
    }

    private static String resolve(RateLimitProperties properties, String xff, String realIp) {
        return new ClientIpResolver(properties).resolve(request(xff, realIp));
    }

    @Test
    void withoutTrustTheSocketPeerWinsNoMatterWhatTheCallerClaims() {
        assertThat(resolve(props(false, 2), "1.2.3.4, 5.6.7.8", "9.9.9.9")).isEqualTo(PEER);
    }

    @Test
    void oneProxyMeansTheOnlyChainEntryIsTheClient() {
        // client -> nginx -> app
        assertThat(resolve(props(true, 1), "197.221.0.5", null)).isEqualTo("197.221.0.5");
    }

    @Test
    void twoProxiesCountPastTheCdnHop() {
        // client -> Cloudflare -> nginx -> app; nginx appended the CF edge.
        assertThat(resolve(props(true, 2), "197.221.0.5, 172.70.0.1", null))
                .isEqualTo("197.221.0.5");
    }

    @Test
    void threeProxiesCountPastAnFeProxyToo() {
        // client -> FE function -> Cloudflare -> nginx -> app
        assertThat(resolve(props(true, 3), "197.221.0.5, 76.76.21.1, 172.70.0.1", null))
                .isEqualTo("197.221.0.5");
    }

    /**
     * THE ONE THAT MATTERS. A caller prepends a forged entry hoping for a
     * private bucket; counting from the right steps over it and lands on the
     * address the outermost trusted proxy actually observed.
     */
    @Test
    void aForgedLeadingEntryIsSteppedOverNotBelieved() {
        String resolved = resolve(props(true, 2), "203.0.113.66, 197.221.0.5, 172.70.0.1", null);

        assertThat(resolved).isEqualTo("197.221.0.5");
        assertThat(resolved).isNotEqualTo("203.0.113.66");
    }

    @Test
    void everyForgedEntryLandsOnTheSameBucketSoSpoofingBuysNothing() {
        RateLimitProperties properties = props(true, 2);

        String first = resolve(properties, "1.1.1.1, 197.221.0.5, 172.70.0.1", null);
        String second = resolve(properties, "2.2.2.2, 197.221.0.5, 172.70.0.1", null);
        String third = resolve(properties, "3.3.3.3, 4.4.4.4, 197.221.0.5, 172.70.0.1", null);

        assertThat(first).isEqualTo(second).isEqualTo(third).isEqualTo("197.221.0.5");
    }

    /**
     * A chain shorter than the configured hops means the request did not come
     * through the proxies we trust — a direct hit on the origin, or a
     * misconfiguration. Believing the header there is exactly the bypass this
     * class exists to prevent.
     */
    @Test
    void aChainShorterThanTheHopCountFallsBackToTheSocketPeer() {
        assertThat(resolve(props(true, 3), "203.0.113.66", null)).isEqualTo(PEER);
        assertThat(resolve(props(true, 2), "203.0.113.66", null)).isEqualTo(PEER);
    }

    @Test
    void zeroHopsKeepsTheOldLeftmostBehaviourForCompatibility() {
        // Spoofable — hence the startup warning. Preserved so enabling the
        // count is an explicit, separate decision from enabling trust.
        assertThat(resolve(props(true, 0), "203.0.113.66, 197.221.0.5", null))
                .isEqualTo("203.0.113.66");
    }

    @Test
    void xRealIpIsUsedOnlyWhenThereIsNoChainToCount() {
        assertThat(resolve(props(true, 1), null, "197.221.0.5")).isEqualTo("197.221.0.5");
        // A countable chain always wins over the single-value header.
        assertThat(resolve(props(true, 1), "197.221.0.7", "197.221.0.5")).isEqualTo("197.221.0.7");
    }

    @Test
    void blankOrAbsentHeadersFallBackToTheSocketPeer() {
        assertThat(resolve(props(true, 1), null, null)).isEqualTo(PEER);
        assertThat(resolve(props(true, 1), "   ", null)).isEqualTo(PEER);
        assertThat(resolve(props(true, 1), " , ", null)).isEqualTo(PEER);
    }

    @Test
    void surroundingWhitespaceInTheChainIsTrimmed() {
        assertThat(resolve(props(true, 2), "  197.221.0.5 ,  172.70.0.1 ", null))
                .isEqualTo("197.221.0.5");
    }
}
