package zw.co.innbucks.middleware.ratelimit;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Works out who the caller actually is, for rate-limiting purposes, when the
 * app sits behind proxies.
 *
 * <p>This matters because the socket peer is useless the moment a proxy is in
 * front: every request arrives from the same address, so one abusive client
 * exhausts the bucket for everybody. The fix is to read {@code
 * X-Forwarded-For} — but reading it naively is WORSE than not reading it at
 * all, because the header is caller-supplied. Anyone who can reach this app
 * directly can put a fresh fake address in it on every request and never be
 * limited again.
 *
 * <p><b>So the chain is counted from the RIGHT.</b> Each proxy APPENDS the
 * address it received the request from, so with {@code N} trusted proxies in
 * front, the rightmost {@code N-1} entries were written by them (the socket
 * peer is the Nth) and everything further left is whatever the client chose to
 * send. Taking {@code length - N} lands on the last entry a client cannot
 * forge:
 *
 * <pre>
 *   client → nginx → app          XFF: "C"                N=1 → index 0 → C
 *   client → CF → nginx → app     XFF: "C, CF"            N=2 → index 0 → C
 *   attacker sends "FAKE" first   XFF: "FAKE, C, CF"      N=2 → index 1 → C  ✅
 * </pre>
 *
 * <p>A chain SHORTER than the configured hop count means the request did not
 * come through the expected proxies — misconfiguration, or someone reaching
 * the origin directly. Either way the header is not trustworthy, so it is
 * ignored and the socket peer is used.
 *
 * <p><b>Precondition, and it is not optional:</b> this is only sound if the
 * origin accepts traffic ONLY from those proxies. If the app's port is
 * reachable from the internet, an attacker skips the proxies entirely, sends
 * whatever chain they like, and the limiter is decorative. Firewall the origin
 * to the proxy/CDN ranges before relying on this.
 */
@Slf4j
@Component
public class ClientIpResolver {

    static final String FORWARDED_FOR = "X-Forwarded-For";
    static final String REAL_IP = "X-Real-IP";

    private final RateLimitProperties properties;

    public ClientIpResolver(RateLimitProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void warnOnUncountedChain() {
        if (!properties.enabled() || !properties.trustForwardedFor()) {
            return;
        }
        if (properties.trustedProxyCount() <= 0) {
            log.warn("════════════════════════════════════════════════════════════════════");
            log.warn(" ⚠️  RATE_LIMIT_TRUST_FORWARDED_FOR is on but RATE_LIMIT_TRUSTED_PROXY_COUNT");
            log.warn("     is 0, so the LEFTMOST X-Forwarded-For entry is used — a value any");
            log.warn("     caller can set. Anyone able to reach this origin directly can mint a");
            log.warn("     fresh address per request and bypass every per-IP limit.");
            log.warn("     Set the count to the number of proxies in front (nginx=1, +CDN=2, ...)");
            log.warn("     AND firewall the origin to those proxies.");
            log.warn("════════════════════════════════════════════════════════════════════");
        } else {
            log.info("Rate limiting resolves the client IP from {} counting {} trusted proxy hop(s) "
                            + "from the right; sound ONLY while the origin refuses direct traffic.",
                    FORWARDED_FOR, properties.trustedProxyCount());
        }
    }

    public String resolve(HttpServletRequest request) {
        String peer = request.getRemoteAddr();
        if (!properties.trustForwardedFor()) {
            return peer;
        }

        String forwarded = request.getHeader(FORWARDED_FOR);
        if (forwarded != null && !forwarded.isBlank()) {
            String[] hops = forwarded.split(",");
            int hopCount = properties.trustedProxyCount();
            // 0 = un-counted legacy behaviour: leftmost, spoofable, warned about
            // at startup. Anything else indexes from the right as documented.
            int index = hopCount <= 0 ? 0 : hops.length - hopCount;
            if (index >= 0 && index < hops.length) {
                String candidate = hops[index].trim();
                if (!candidate.isEmpty()) {
                    log.debug("Client IP {} resolved from {} chain [{}] (hops={}, peer={})",
                            candidate, FORWARDED_FOR, forwarded, hopCount, peer);
                    return candidate;
                }
            }
            // Chain shorter than expected: not the route we trust. Fall through
            // to the socket peer rather than believe a client-supplied value.
            log.debug("{} chain [{}] has {} entr(ies), fewer than the {} trusted hop(s) configured "
                            + "— falling back to the socket peer",
                    FORWARDED_FOR, forwarded, hops.length, hopCount);
            return peer;
        }

        // No chain to count. X-Real-IP is a single value set by the nearest
        // proxy, so it survives only one hop and carries no evidence of where
        // it came from — accepted as a convenience for a single-proxy
        // deployment, never preferred over a countable chain.
        String realIp = request.getHeader(REAL_IP);
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return peer;
    }
}
