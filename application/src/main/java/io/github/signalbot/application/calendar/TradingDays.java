package io.github.signalbot.application.calendar;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * The one trading-day rule of the service (used by the earnings trigger and the earnings blackout, D-006).
 * Trading days are approximated as weekdays: exchange holidays are ignored for now.
 */
public final class TradingDays {

    /** Dates further than this many calendar days ahead are never "within" any window. */
    private static final int HORIZON_CALENDAR_DAYS = 14;

    private TradingDays() {
    }

    /**
     * Whether {@code target} lies at most {@code maxTradingDays} trading days after {@code from}.
     * {@code from} itself counts as day 0, so {@code target == from} is always within (even with max 0 or on a
     * weekend). A past target, a target more than 14 calendar days ahead, or a later target on a weekend is not.
     */
    public static boolean withinTradingDays(LocalDate from, LocalDate target, int maxTradingDays) {
        if (target.isBefore(from) || target.isAfter(from.plusDays(HORIZON_CALENDAR_DAYS))) {
            return false;
        }
        int tradingDays = 0;
        for (LocalDate d = from.plusDays(1); !d.isAfter(target); d = d.plusDays(1)) {
            if (isTradingDay(d)) {
                tradingDays++;
            }
        }
        return tradingDays <= maxTradingDays && (isTradingDay(target) || target.equals(from));
    }

    /** Monday to Friday; holidays are not known. */
    public static boolean isTradingDay(LocalDate date) {
        return date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY;
    }
}
