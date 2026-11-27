package io.github.signalbot.application.risk;

import io.github.signalbot.domain.model.EarningsEvent;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EarningsBlackoutTest {

    // Thursday 2026-10-22 as "today": Fri 23 is the next day, Mon 26 the second trading day, Tue 27 the third.
    private static final LocalDate THU = LocalDate.of(2026, 10, 22);
    private static final LocalDate FRI = LocalDate.of(2026, 10, 23);
    private static final LocalDate MON = LocalDate.of(2026, 10, 26);
    private static final LocalDate TUE = LocalDate.of(2026, 10, 27);

    private static List<EarningsEvent> on(LocalDate date) {
        return List.of(new EarningsEvent("AAPL", date));
    }

    @Test
    void earningsTodayBlocks() {
        assertEquals(Optional.of("earnings on 2026-10-22, within 2 trading days"),
                EarningsBlackout.blocks(THU, on(THU), 2));
    }

    @Test
    void earningsTomorrowBlocks() {
        assertEquals(Optional.of("earnings on 2026-10-23, within 2 trading days"),
                EarningsBlackout.blocks(THU, on(FRI), 2));
    }

    @Test
    void earningsOnSecondTradingDayBlocks() {
        assertEquals(Optional.of("earnings on 2026-10-26, within 2 trading days"),
                EarningsBlackout.blocks(THU, on(MON), 2));
    }

    @Test
    void earningsInThreeTradingDaysDoNotBlock() {
        assertTrue(EarningsBlackout.blocks(THU, on(TUE), 2).isEmpty());
    }

    @Test
    void fridayToMondayCountsOneTradingDay() {
        assertEquals(Optional.of("earnings on 2026-10-26, within 1 trading days"),
                EarningsBlackout.blocks(FRI, on(MON), 1));
        assertTrue(EarningsBlackout.blocks(FRI, on(TUE), 1).isEmpty());
    }

    @Test
    void zeroNeverBlocksEvenForEarningsToday() {
        assertTrue(EarningsBlackout.blocks(THU, on(THU), 0).isEmpty());
        assertTrue(EarningsBlackout.blocks(THU, on(FRI), 0).isEmpty());
    }

    @Test
    void negativeNeverBlocks() {
        assertTrue(EarningsBlackout.blocks(THU, on(THU), -1).isEmpty());
    }

    @Test
    void noEventsNeverBlock() {
        assertTrue(EarningsBlackout.blocks(THU, List.of(), 2).isEmpty());
    }

    @Test
    void pastEarningsDoNotBlock() {
        assertTrue(EarningsBlackout.blocks(THU, on(THU.minusDays(1)), 2).isEmpty());
    }

    @Test
    void earliestEventInWindowIsNamed() {
        List<EarningsEvent> events = List.of(
                new EarningsEvent("AAPL", TUE),
                new EarningsEvent("AAPL", MON),
                new EarningsEvent("AAPL", FRI));
        assertEquals(Optional.of("earnings on 2026-10-23, within 2 trading days"),
                EarningsBlackout.blocks(THU, events, 2));
    }
}
