package io.github.signalbot.trigger;

import io.github.signalbot.application.trigger.ExitGuard;
import io.github.signalbot.application.trigger.TrailingState;
import io.github.signalbot.persistence.PositionGuardStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persists a symbol's trailing state (D-004: a restart must not reset trailing). Called after each
 * {@link ExitGuard#onPrice}; writes {@code best_price}/{@code activated} only when the state changed to an
 * activated one (activation or a new best), not on every tick.
 */
@Component
public class TrailingStateWriter {

    private static final Logger LOG = LoggerFactory.getLogger(TrailingStateWriter.class);

    private final ExitGuard exitGuard;
    private final PositionGuardStore guardStore;
    private final Map<String, TrailingState> lastSaved = new ConcurrentHashMap<>();

    public TrailingStateWriter(ExitGuard exitGuard, PositionGuardStore guardStore) {
        this.exitGuard = exitGuard;
        this.guardStore = guardStore;
    }

    /** Records the state of a guard restored from storage, so the first tick after a restart does not rewrite it. */
    public void remember(String symbol, TrailingState state) {
        if (state.activated()) {
            lastSaved.put(symbol, state);
        } else {
            lastSaved.remove(symbol);
        }
    }

    /** Compares the symbol's trailing state with the last saved one and writes it when it is new and activated. */
    public synchronized void afterPrice(String symbol) {
        Optional<TrailingState> current = exitGuard.state(symbol);
        if (current.isEmpty() || !current.get().activated()) {
            // Disarmed, stop only, or armed afresh: nothing to write (the fill wrote the reset state).
            lastSaved.remove(symbol);
            return;
        }
        TrailingState state = current.get();
        if (state.equals(lastSaved.get(symbol))) {
            return;
        }
        try {
            guardStore.saveTrailingState(symbol, state);
            lastSaved.put(symbol, state);
        } catch (RuntimeException e) {
            // Not remembered, so the next tick tries again; the in-memory guard keeps working meanwhile.
            LOG.warn("Could not save the trailing state of {}: {}", symbol, e.getMessage());
        }
    }
}
