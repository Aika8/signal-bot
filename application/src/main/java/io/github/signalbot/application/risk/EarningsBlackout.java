package io.github.signalbot.application.risk;

import io.github.signalbot.application.calendar.TradingDays;
import io.github.signalbot.domain.model.EarningsEvent;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Earnings blackout (D-006): no new BUY when the company reports earnings within N trading days, today included.
 * Pure: the caller supplies "today" (the {@code America/New_York} date), the symbol's events and N from
 * configuration. N = 0 (or less) switches the blackout off.
 */
public final class EarningsBlackout {

    private EarningsBlackout() {
    }

    /**
     * The rejection reason when an earnings event falls within {@code tradingDays} trading days of {@code today}
     * (the earliest such event is named), otherwise empty.
     */
    public static Optional<String> blocks(LocalDate today, List<EarningsEvent> events, int tradingDays) {
        if (tradingDays <= 0) {
            return Optional.empty();
        }
        return events.stream()
                .map(EarningsEvent::date)
                .filter(date -> TradingDays.withinTradingDays(today, date, tradingDays))
                .min(Comparator.naturalOrder())
                .map(date -> "earnings on " + date + ", within " + tradingDays + " trading days");
    }
}
