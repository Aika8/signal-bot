package io.github.signalbot.application.trigger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects the three trigger types from incoming events. Pure (no I/O); holds
 * only in-memory reference state. Debounce is applied separately by the caller.
 *
 * <ul>
 *   <li>T1 PRICE_MOVE: price moved &ge; {@code priceMovePct} from the reference
 *       price within {@code priceWindow}; the reference is the last verdict price
 *       (or entry, set via {@link #setReferencePrice}).</li>
 *   <li>T2 NEWS: a news item for a watchlist symbol (freshness decided upstream).</li>
 *   <li>T3 EARNINGS: an eligible earnings date, fired once per symbol+date.</li>
 * </ul>
 */
public final class TriggerEvaluator {

    private record PriceRef(BigDecimal price, Instant at) {
    }

    private final TriggerSettings settings;
    private final Map<String, PriceRef> references = new ConcurrentHashMap<>();
    private final Set<String> firedEarnings = ConcurrentHashMap.newKeySet();

    public TriggerEvaluator(TriggerSettings settings) {
        this.settings = settings;
    }

    /** Sets the reference price a price move is measured against (e.g. after a verdict). */
    public void setReferencePrice(String symbol, BigDecimal price, Instant at) {
        references.put(symbol, new PriceRef(price, at));
    }

    public synchronized Optional<TriggerSignal> onPriceTick(String symbol, BigDecimal price, Instant now) {
        PriceRef ref = references.get(symbol);
        if (ref == null || expired(ref, now)) {
            references.put(symbol, new PriceRef(price, now));
            return Optional.empty();
        }
        BigDecimal movePct = price.subtract(ref.price()).abs()
                .divide(ref.price(), 6, RoundingMode.HALF_UP);
        if (movePct.compareTo(settings.priceMovePct()) >= 0) {
            references.put(symbol, new PriceRef(price, now)); // reset so it does not re-fire every tick
            String reason = "price moved " + movePct.movePointRight(2).setScale(2, RoundingMode.HALF_UP)
                    + "% within " + settings.priceWindow().toMinutes() + "m";
            return Optional.of(new TriggerSignal(symbol, TriggerType.PRICE_MOVE, reason, now));
        }
        return Optional.empty();
    }

    public Optional<TriggerSignal> onNews(String symbol, String headline, Instant now) {
        return Optional.of(new TriggerSignal(symbol, TriggerType.NEWS, "news: " + headline, now));
    }

    /** Fires once per symbol+date; the "within N trading days" window is enforced by the caller. */
    public Optional<TriggerSignal> onEarnings(String symbol, LocalDate earningsDate, Instant now) {
        String key = symbol + "|" + earningsDate;
        if (!firedEarnings.add(key)) {
            return Optional.empty();
        }
        return Optional.of(new TriggerSignal(symbol, TriggerType.EARNINGS, "earnings on " + earningsDate, now));
    }

    private boolean expired(PriceRef ref, Instant now) {
        return Duration.between(ref.at(), now).compareTo(settings.priceWindow()) > 0;
    }
}
