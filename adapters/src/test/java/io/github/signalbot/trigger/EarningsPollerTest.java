package io.github.signalbot.trigger;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EarningsPollerTest {

    // Thursday 2026-10-22 as the reference "today".
    private static final LocalDate THU = LocalDate.of(2026, 10, 22);

    @Test
    void nextDayIsWithinTwoTradingDays() {
        assertTrue(EarningsPoller.withinTradingDays(THU, THU.plusDays(1), 2)); // Fri
    }

    @Test
    void acrossWeekendStillCountsTradingDays() {
        // Fri (+1) and Mon (+2) are the first two trading days; Mon is 2026-10-26.
        assertTrue(EarningsPoller.withinTradingDays(THU, LocalDate.of(2026, 10, 26), 2));
    }

    @Test
    void thirdTradingDayIsOutsideWindow() {
        // Tue 2026-10-27 is the 3rd trading day after Thu.
        assertFalse(EarningsPoller.withinTradingDays(THU, LocalDate.of(2026, 10, 27), 2));
    }

    @Test
    void pastDateIsExcluded() {
        assertFalse(EarningsPoller.withinTradingDays(THU, THU.minusDays(1), 2));
    }
}
