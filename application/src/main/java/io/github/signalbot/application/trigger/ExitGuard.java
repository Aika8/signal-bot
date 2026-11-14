package io.github.signalbot.application.trigger;

import java.math.BigDecimal;
import java.util.Map;
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
 */
public final class ExitGuard {

    private record Levels(BigDecimal stop, BigDecimal takeProfit, boolean stopFired, boolean tpFired) {
    }

    private final Map<String, Levels> armed = new ConcurrentHashMap<>();

    /** Sets (or resets) the stop/take-profit levels for a symbol and re-arms both. */
    public void arm(String symbol, BigDecimal stop, BigDecimal takeProfit) {
        armed.put(symbol, new Levels(stop, takeProfit, false, false));
    }

    public void disarm(String symbol) {
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
            armed.put(symbol, new Levels(l.stop(), l.takeProfit(), true, l.tpFired()));
            return Optional.of(new ExitSignal(symbol, ExitType.EXIT_STOP, price, l.stop()));
        }
        if (l.takeProfit() != null && !l.tpFired() && price.compareTo(l.takeProfit()) >= 0) {
            armed.put(symbol, new Levels(l.stop(), l.takeProfit(), l.stopFired(), true));
            return Optional.of(new ExitSignal(symbol, ExitType.EXIT_TP, price, l.takeProfit()));
        }
        return Optional.empty();
    }
}
