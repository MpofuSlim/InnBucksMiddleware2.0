package zw.co.innbucks.middleware.transactions;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import zw.co.innbucks.middleware.common.country.CountryProperties;
import zw.co.innbucks.middleware.common.msisdn.MsisdnMasking;
import zw.co.innbucks.middleware.common.msisdn.MsisdnNormalizerRegistry;
import zw.co.innbucks.middleware.corebanking.CoreBankingPort;
import zw.co.innbucks.middleware.corebanking.value.CoreCustomerRef;
import zw.co.innbucks.middleware.corebanking.value.CustomerProfile;
import zw.co.innbucks.middleware.corebanking.value.DepositAccountRef;
import zw.co.innbucks.middleware.customer.Customer;
import zw.co.innbucks.middleware.customer.CustomerRepository;
import zw.co.innbucks.middleware.ratelimit.RateLimitDecision;
import zw.co.innbucks.middleware.ratelimit.RateLimitExceededException;
import zw.co.innbucks.middleware.ratelimit.RateLimitProperties;
import zw.co.innbucks.middleware.ratelimit.RateLimiterService;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Resolves a phone number to a transferable wallet — the P2P affordance every
 * mobile-money rail has: the sender types a number, the app shows "Send to
 * Tariro M.?", and the confirmed accountId goes into the transfer call.
 *
 * <p><b>This endpoint is an enumeration oracle by nature</b> — "is this MSISDN
 * one of our customers, and whose" is exactly what it answers — so its guard
 * rails are the design, not decoration:
 *
 * <ul>
 *   <li><b>Authenticated callers only</b>, so every probe is attributable to a
 *       real customer with a verified phone and PIN.</li>
 *   <li><b>Per-CUSTOMER rate limit</b> (not per-IP: NAT would let one abuser
 *       spend a whole office's budget, and a botnet would dodge per-IP
 *       entirely). At the default 15/min, sweeping Zimbabwe's ~10^7 mobile
 *       numbers takes over a year per account.</li>
 *   <li><b>Minimum disclosure:</b> a display name masked to first name +
 *       surname initial — enough to catch a typo'd digit before money moves,
 *       not enough to harvest identities. Never balance, never status, never
 *       KYC tier.</li>
 *   <li><b>One indistinguishable not-found.</b> Unknown number, unregistered
 *       customer, no core mapping, no active wallet — all the same 404, so the
 *       endpoint can't be used to distinguish "not a customer" from
 *       "customer, mid-onboarding".</li>
 * </ul>
 *
 * <p>Deliberately NOT anti-enumeration-by-vagueness like login: the endpoint's
 * whole purpose is confirming a recipient exists. The protection is cost
 * (auth + budget) and disclosure-minimisation, which is how the incumbent P2P
 * rails (M-Pesa, EcoCash) handle the same trade-off.
 */
@Slf4j
@Service
public class RecipientLookupService {

    private final CustomerRepository customerRepository;
    private final MsisdnNormalizerRegistry msisdnRegistry;
    private final CountryProperties countryProperties;
    private final CoreBankingPort corePort;
    private final RateLimiterService rateLimiter;
    private final RateLimitProperties rateLimitProperties;
    private final MeterRegistry meterRegistry;

    public RecipientLookupService(CustomerRepository customerRepository,
                                  MsisdnNormalizerRegistry msisdnRegistry,
                                  CountryProperties countryProperties,
                                  CoreBankingPort corePort,
                                  RateLimiterService rateLimiter,
                                  RateLimitProperties rateLimitProperties,
                                  MeterRegistry meterRegistry) {
        this.customerRepository = customerRepository;
        this.msisdnRegistry = msisdnRegistry;
        this.countryProperties = countryProperties;
        this.corePort = corePort;
        this.rateLimiter = rateLimiter;
        this.rateLimitProperties = rateLimitProperties;
        this.meterRegistry = meterRegistry;
    }

    public RecipientView lookup(UUID callerId, String rawMsisdn) {
        // Budget FIRST, before any answer-bearing work — a rate-limited caller
        // must learn nothing, not even "that number was invalid".
        if (rateLimitProperties.enabled()) {
            RateLimitDecision decision = rateLimiter.tryConsume(
                    "customer:lookup:" + callerId, rateLimitProperties.customerLookup());
            if (!decision.allowed()) {
                count("rate_limited");
                throw new RateLimitExceededException(decision.retryAfterSeconds());
            }
        }

        // InvalidMsisdnException propagates -> 400 invalid_msisdn: the number's
        // SHAPE is client-side knowledge already, and a typo the app's own
        // validation missed should read as a typo, not as "no such customer".
        String msisdn = msisdnRegistry.forDeployment().normalize(rawMsisdn);

        Customer customer = customerRepository
                .findByCountryAndMsisdn(countryProperties.country().name(), msisdn)
                .orElseThrow(() -> notFound("no_customer", msisdn));
        if (customer.getCoreExternalId() == null) {
            // Registration crashed before the core mapping landed — there is
            // no wallet to send to yet.
            throw notFound("no_core_mapping", msisdn);
        }

        // The core's account list is the source of truth for WHERE money can
        // go — the same rule as the movement endpoints' ownership checks. The
        // <uuid>:wallet convention picks the wallet out of the list; it is
        // never trusted on its own.
        List<DepositAccountRef> accounts =
                corePort.listDepositAccountRefs(new CoreCustomerRef(customer.getCoreExternalId()));
        String walletId = customer.getId() + ":wallet";
        DepositAccountRef wallet = accounts.stream()
                .filter(a -> a.account().externalId().equals(walletId))
                .findFirst()
                .orElseGet(() -> accounts.isEmpty() ? null : accounts.get(0));
        if (wallet == null) {
            throw notFound("no_account", msisdn);
        }

        CustomerProfile profile = corePort.getProfile(new CoreCustomerRef(customer.getCoreExternalId()));
        count("found");
        return new RecipientView(
                wallet.account().externalId(),
                maskName(profile.firstName(), profile.lastName()),
                msisdn);
    }

    /**
     * "Tariro Moyo" → "Tariro M." — enough for the sender to catch a wrong
     * digit before money moves, not enough to harvest identities by number.
     */
    static String maskName(String firstName, String lastName) {
        String first = firstName == null ? "" : firstName.trim();
        String last = lastName == null ? "" : lastName.trim();
        if (first.isEmpty() && last.isEmpty()) {
            return "InnBucks customer";
        }
        if (last.isEmpty()) {
            return first;
        }
        String initial = last.substring(0, 1).toUpperCase(Locale.ROOT) + ".";
        return first.isEmpty() ? initial : first + " " + initial;
    }

    private RecipientNotFoundException notFound(String reason, String msisdn) {
        // The REASON stays server-side (log + metric); the caller sees one
        // undifferentiated 404 regardless.
        count(reason);
        log.info("Recipient lookup miss ({}) for {}", reason, MsisdnMasking.mask(msisdn));
        return new RecipientNotFoundException();
    }

    private void count(String outcome) {
        Counter.builder("innbucks.recipient.lookup")
                .description("Phone-number recipient lookups by outcome — a rate_limited or miss spike is an enumeration probe")
                .tag("outcome", outcome)
                .register(meterRegistry)
                .increment();
    }
}
