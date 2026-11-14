package io.github.signalbot.trigger;

import io.github.signalbot.application.trigger.TriggerEvaluator;
import io.github.signalbot.config.TriggerProperties;
import io.github.signalbot.config.WatchlistProperties;
import io.github.signalbot.domain.model.EarningsEvent;
import io.github.signalbot.domain.port.EarningsCalendar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Daily poll for upcoming earnings; raises T3 once per symbol+date when earnings
 * are within the configured number of trading days. Trading days are approximated
 * as weekdays (holidays are ignored for now).
 */
@Component
public class EarningsPoller {

    private static final Logger LOG = LoggerFactory.getLogger(EarningsPoller.class);

    private final ObjectProvider<EarningsCalendar> earningsCalendar;
    private final WatchlistProperties watchlist;
    private final TriggerProperties props;
    private final TriggerEvaluator evaluator;
    private final SignalDispatcher dispatcher;

    public EarningsPoller(ObjectProvider<EarningsCalendar> earningsCalendar, WatchlistProperties watchlist,
                          TriggerProperties props, TriggerEvaluator evaluator, SignalDispatcher dispatcher) {
        this.earningsCalendar = earningsCalendar;
        this.watchlist = watchlist;
        this.props = props;
        this.evaluator = evaluator;
        this.dispatcher = dispatcher;
    }

    @Scheduled(cron = "${signalbot.trigger.earnings-poll-cron}")
    public void poll() {
        EarningsCalendar calendar = earningsCalendar.getIfAvailable();
        if (calendar == null) {
            LOG.debug("Earnings calendar disabled; skipping poll.");
            return;
        }
        LocalDate today = LocalDate.now();
        LocalDate horizon = today.plusDays(14);
        Instant now = Instant.now();
        for (String symbol : watchlist.symbols()) {
            try {
                for (EarningsEvent event : calendar.upcoming(symbol, today, horizon)) {
                    if (withinTradingDays(today, event.date(), props.earningsWithinTradingDays())) {
                        evaluator.onEarnings(symbol, event.date(), now).ifPresent(dispatcher::dispatch);
                    }
                }
            } catch (Exception e) {
                LOG.warn("Earnings poll failed for {}: {}", symbol, e.getMessage());
            }
        }
    }

    static boolean withinTradingDays(LocalDate from, LocalDate target, int maxTradingDays) {
        if (target.isBefore(from) || target.isAfter(from.plusDays(14))) {
            return false;
        }
        int tradingDays = 0;
        for (LocalDate d = from.plusDays(1); !d.isAfter(target); d = d.plusDays(1)) {
            if (isWeekday(d)) {
                tradingDays++;
            }
        }
        return tradingDays <= maxTradingDays && (isWeekday(target) || target.equals(from));
    }

    private static boolean isWeekday(LocalDate date) {
        return date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY;
    }
}
