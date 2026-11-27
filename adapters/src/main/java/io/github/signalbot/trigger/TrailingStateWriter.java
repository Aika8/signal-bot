package io.github.signalbot.trigger;

import io.github.signalbot.application.trigger.ExitGuard;
import io.github.signalbot.application.trigger.TrailingSnapshot;
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
 *
 * <p>The levels and the state are read together ({@link ExitGuard#snapshot}) and the store writes the state only onto
 * the guard row with those levels (#30): if a fill re-armed the row with new levels in between, nothing is written.
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
        Optional<TrailingSnapshot> current = exitGuard.snapshot(symbol);
        if (current.isEmpty() || !current.get().activated()) {
            // Disarmed, stop only, or armed afresh: nothing to write (the fill wrote the reset state).
            lastSaved.remove(symbol);
            return;
        }
        TrailingSnapshot snapshot = current.get();
        TrailingState state = snapshot.state();
        if (state.equals(lastSaved.get(symbol))) {
            return;
        }
        try {
            if (!guardStore.saveTrailingState(symbol, snapshot)) {
                // The stored guard holds other levels (re-armed by a fill) or is gone: this state is not its own.
                // Remembered anyway, so the same stale state is not retried on every tick.
                LOG.info("Trailing state of {} not saved: no stored guard with activation {} and offset {} (0 rows)",
                        symbol, snapshot.levels().activation(), snapshot.levels().offset());
            }
            lastSaved.put(symbol, state);
        } catch (RuntimeException e) {
            // Not remembered, so the next tick tries again; the in-memory guard keeps working meanwhile.
            LOG.warn("Could not save the trailing state of {}: {}", symbol, e.getMessage());
        }
    }
}
