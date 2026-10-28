package zw.co.innbucks.middleware.transactions;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import zw.co.innbucks.middleware.config.ProductionSecretsGuard;

import java.util.Arrays;
import java.util.UUID;

/**
 * Keeps {@code POST /transactions/deposit} shut unless the cell opts in.
 *
 * <p>The endpoint is an UNFUNDED cash-in. Every customer token carries
 * {@code customer:write}, and a deposit books a plain savings deposit (the
 * cell's non-cash wallet payment type) of any amount into the caller's own
 * wallet: no source of funds, no step-up, no maximum, and the wallet product's
 * accounting rule is None, so the core keeps no GL trail of where the money
 * came from. Open, any customer can credit themselves from nothing and then
 * spend it. That is useful on staging, where testers have to fund wallets
 * somehow, and a go-live blocker anywhere real money is held. Production stays
 * off until a FUNDED cash-in rail exists, and that rail is a new design, not
 * this flag turned on.
 *
 * <p>Off, the refusal is the FIRST thing {@code MovementService.deposit}
 * does: before the customer read, the ownership call to the core, the
 * idempotency claim and the ledger row. It writes nothing. Like
 * {@code step_up_required}, it is a counter and an INFO line, not an audit
 * row: the counter answers "does the app still call it?" once a cell turns it
 * off, without anyone reading logs.
 */
@Slf4j
@Component
@EnableConfigurationProperties(DepositProperties.class)
public class DepositGate {

    private final DepositProperties properties;
    private final Environment environment;
    private final Counter refused;

    public DepositGate(DepositProperties properties, Environment environment, MeterRegistry meterRegistry) {
        this.properties = properties;
        this.environment = environment;
        // Registered at boot, so a cell with the switch off reads 0 rather
        // than "no such series".
        this.refused = meterRegistry.counter("innbucks.transactions.deposit.refused", "reason", "disabled");
    }

    /** @throws DepositDisabledException the cell has not opted in (403 deposit_disabled) */
    public void requireEnabled(UUID customerId) {
        if (properties.enabled()) {
            return;
        }
        refused.increment();
        log.info("Deposit refused customer={}: customer deposits are disabled in this deployment", customerId);
        throw new DepositDisabledException();
    }

    /**
     * Not fail-closed: staging is a deployment profile and runs with deposits
     * on by design. Loud on every boot instead, so a cell that carries the
     * staging value somewhere it should not is seen at the first restart.
     */
    @PostConstruct
    void announce() {
        if (!properties.enabled()) {
            log.info("Customer deposits: OFF — POST /transactions/deposit answers 403 deposit_disabled");
            return;
        }
        if (!ProductionSecretsGuard.isDeployment(environment)) {
            log.info("Customer deposits: ON (non-deployment profiles {})",
                    Arrays.toString(environment.getActiveProfiles()));
            return;
        }
        log.warn("════════════════════════════════════════════════════════════════════");
        log.warn(" CUSTOMER DEPOSITS ARE ON under deployment profiles {} —",
                Arrays.toString(environment.getActiveProfiles()));
        log.warn("     POST /transactions/deposit is an UNFUNDED cash-in: any signed-in customer");
        log.warn("     can credit their own wallet with any amount, with no source of funds and");
        log.warn("     no GL trail. Staging only. NEVER set TRANSACTIONS_DEPOSIT_ENABLED=true");
        log.warn("     on a cell that holds real money.");
        log.warn("════════════════════════════════════════════════════════════════════");
    }
}
