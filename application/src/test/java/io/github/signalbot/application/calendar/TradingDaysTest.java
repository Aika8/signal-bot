package io.github.signalbot.application.calendar;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradingDaysTest {

    // Thursday 2026-10-22 as the reference "today" (same as EarningsPollerTest).
    private static final LocalDate THU = LocalDate.of(2026, 10, 22);
    private static final LocalDate SAT = LocalDate.of(2026, 10, 24);
    private static final LocalDate MON = LocalDate.of(2026, 10, 26);

    @Test
    void nextDayIsWithinTwoTradingDays() {
        assertTrue(TradingDays.withinTradingDays(THU, THU.plusDays(1), 2)); // Fri
    }

    @Test
    void acrossWeekendStillCountsTradingDays() {
        assertTrue(TradingDays.withinTradingDays(THU, MON, 2));
    }

    @Test
    void thirdTradingDayIsOutsideWindow() {
        assertFalse(TradingDays.withinTradingDays(THU, LocalDate.of(2026, 10, 27), 2));
    }

    @Test
    void pastDateIsExcluded() {
        assertFalse(TradingDays.withinTradingDays(THU, THU.minusDays(1), 2));
    }

    @Test
    void weekendTargetIsNotWithin() {
        assertFalse(TradingDays.withinTradingDays(THU, SAT, 2));
        assertFalse(TradingDays.withinTradingDays(THU, SAT.plusDays(1), 2));
    }

    @Test
    void weekendTodayCountsAsDayZero() {
        assertTrue(TradingDays.withinTradingDays(SAT, SAT, 0));
        assertTrue(TradingDays.withinTradingDays(SAT, MON, 1));
        assertFalse(TradingDays.withinTradingDays(SAT, MON, 0));
    }

    @Test
    void maxZeroAllowsOnlyToday() {
        assertTrue(TradingDays.withinTradingDays(THU, THU, 0));
        assertFalse(TradingDays.withinTradingDays(THU, THU.plusDays(1), 0));
    }

    @Test
    void beyondFourteenCalendarDaysIsExcluded() {
        assertTrue(TradingDays.withinTradingDays(THU, THU.plusDays(14), 10));
        assertFalse(TradingDays.withinTradingDays(THU, THU.plusDays(15), 20));
    }
}
