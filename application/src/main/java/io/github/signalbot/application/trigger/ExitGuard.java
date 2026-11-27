package io.github.signalbot.application.trigger;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Watches held positions for stop-loss / take-profit breaches. Pure (no I/O),
 * source-agnostic: the same {@link #onPrice} handles websocket ticks and REST
 * snapshots, so a websocket gap caught by a snapshot still fires.
 *
 * <p>Each level fires at most once; {@link #arm} (called when a new verdict sets
 * the levels) re-arms both levels. A null level is never triggered.
 *
 * <p>A symbol armed with {@link #armTrailing} has a trailing take-profit (D-004) instead of a fixed one: before
 * the activation price the best price is not tracked; from activation on, {@code best = max(best, price)} and the
 * exit fires once when {@code price < best − offset}. The stop stays armed after activation and is checked first,
 * so a gap through both gives the stop.
 *
 * <p>Every method that changes the armed levels ({@link #arm}, {@link #armTrailing}, {@link #restore},
 * {@link #disarm}, {@link #onPrice}) is {@code synchronized} on this guard, so an arm or disarm never lands
 * between the read and the write of {@code onPrice} and gets overwritten by the old state. The read-only
 * {@link #state} and {@link #armedSymbols} read the concurrent map without the lock.
 */
public final class ExitGuard {

    private record Levels(BigDecimal stop, BigDecimal takeProfit, boolean stopFired, boolean tpFired,
                          TrailingLevels trailing, BigDecimal best, boolean activated, boolean trailFired) {

        static Levels fixed(BigDecimal stop, BigDecimal takeProfit) {
            return new Levels(stop, takeProfit, false, false, null, null, false, false);
        }

        static Levels trailing(BigDecimal stop, TrailingLevels trailing, BigDecimal best, boolean activated) {
            return new Levels(stop, null, false, false, trailing, best, activated, false);
        }

        Levels withStopFired() {
            return new Levels(stop, takeProfit, true, tpFired, trailing, best, activated, trailFired);
        }

        Levels withTpFired() {
            return new Levels(stop, takeProfit, stopFired, true, trailing, best, activated, trailFired);
        }

        Levels withTrail(BigDecimal newBest, boolean newActivated, boolean newTrailFired) {
            return new Levels(stop, takeProfit, stopFired, tpFired, trailing, newBest, newActivated, newTrailFired);
        }
    }

    private final Map<String, Levels> armed = new ConcurrentHashMap<>();

    /** Sets (or resets) the stop/take-profit levels for a symbol and re-arms both. */
    public synchronized void arm(String symbol, BigDecimal stop, BigDecimal takeProfit) {
        armed.put(symbol, Levels.fixed(stop, takeProfit));
    }

    /**
     * Sets (or resets) a stop and a trailing take-profit for a symbol (D-004): not activated, no best price, both
     * re-armed. Replaces any fixed take-profit armed before.
     */
    public synchronized void armTrailing(String symbol, BigDecimal stop, TrailingLevels trailing) {
        Objects.requireNonNull(trailing, "trailing");
        armed.put(symbol, Levels.trailing(stop, trailing, null, false));
    }

    /**
     * Re-arms a stop and a trailing take-profit with the persisted trailing state, so a restart does not reset
     * trailing (D-004). {@code best} is {@code null} exactly when {@code activated} is false.
     */
    public synchronized void restore(String symbol, BigDecimal stop, TrailingLevels trailing, BigDecimal best,
                        boolean activated) {
        Objects.requireNonNull(trailing, "trailing");
        TrailingState state = new TrailingState(best, activated);
        armed.put(symbol, Levels.trailing(stop, trailing, state.best(), state.activated()));
    }

    /** The current trailing state of a symbol, or empty when it has no trailing take-profit armed. */
    public Optional<TrailingState> state(String symbol) {
        Levels l = armed.get(symbol);
        if (l == null || l.trailing() == null) {
            return Optional.empty();
        }
        return Optional.of(new TrailingState(l.best(), l.activated()));
    }

    public synchronized void disarm(String symbol) {
        armed.remove(symbol);
    }

    public Set<String> armedSymbols() {
        return Set.copyOf(armed.keySet());
    }

    public synchronized Optional<ExitSignal> onPrice(String symbol, BigDecimal price) {
        Levels l = armed.get(symbol);
        if (l == null || price == null) {
            return Optional.empty();
        }
        if (l.stop() != null && !l.stopFired() && price.compareTo(l.stop()) <= 0) {
            armed.put(symbol, l.withStopFired());
            return Optional.of(new ExitSignal(symbol, ExitType.EXIT_STOP, price, l.stop()));
        }
        if (l.takeProfit() != null && !l.tpFired() && price.compareTo(l.takeProfit()) >= 0) {
            armed.put(symbol, l.withTpFired());
            return Optional.of(new ExitSignal(symbol, ExitType.EXIT_TP, price, l.takeProfit()));
        }
        if (l.trailing() != null && !l.trailFired()) {
            return onTrailingPrice(symbol, l, price);
        }
        return Optional.empty();
    }

    private Optional<ExitSignal> onTrailingPrice(String symbol, Levels l, BigDecimal price) {
        TrailingLevels t = l.trailing();
        if (!l.activated()) {
            if (price.compareTo(t.activation()) >= 0) {
                armed.put(symbol, l.withTrail(price, true, false));
            }
            return Optional.empty();
        }
        BigDecimal exitLevel = l.best().subtract(t.offset());
        if (price.compareTo(exitLevel) < 0) {
            armed.put(symbol, l.withTrail(l.best(), true, true));
            return Optional.of(new ExitSignal(symbol, ExitType.EXIT_TRAIL, price, exitLevel,
                    price.subtract(t.spread())));
        }
        if (price.compareTo(l.best()) > 0) {
            armed.put(symbol, l.withTrail(price, true, false));
        }
        return Optional.empty();
    }
}
