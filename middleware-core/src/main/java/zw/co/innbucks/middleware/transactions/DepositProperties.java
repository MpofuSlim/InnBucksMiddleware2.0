package zw.co.innbucks.middleware.transactions;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The cell switch for {@code POST /transactions/deposit}. OFF unless a cell
 * turns it on: the endpoint credits the caller's own wallet with a plain
 * savings deposit that no money stands behind — see {@link DepositGate}.
 *
 * <p>A primitive {@code boolean}, so a cell whose configuration does not
 * mention the key at all binds {@code false}. Absent means off, never on.
 */
@ConfigurationProperties(prefix = "innbucks.transactions.deposit")
public record DepositProperties(

        /** Staging only (testers fund wallets, the runbook smoke deposits). Never where real money is held. */
        boolean enabled
) {
}
