package zw.co.innbucks.middleware.corebanking;

import zw.co.innbucks.middleware.corebanking.value.CoreMovementObserved;

/**
 * Callback for movements the CORE reports on its own initiative (webhook,
 * platform callback) rather than in response to a middleware call.
 *
 * <p>This is the inverse of {@link CoreBankingPort}: the port is the
 * middleware talking to the core; this is the core talking back. Adapters
 * translate their core's event wire format into {@link CoreMovementObserved}
 * and invoke this — they never know who listens, and the listener never knows
 * which core spoke. Implementations must be safe to call from an adapter's
 * event-receiving thread and must not throw.
 */
public interface CoreMovementListener {

    void onCoreMovement(CoreMovementObserved movement);
}
