package io.github.signalbot.screen;

import io.github.signalbot.domain.model.DailyBar;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** SB-004: the screener's ATR% is Wilder's ATR(14) and too few bars is an error, never 0. */
class ScreenMetricsWilderAtrTest {

    private static final BigDecimal LAST = new BigDecimal("100");

    private static List<DailyBar> flatBars(int count) {
        List<DailyBar> bars = new ArrayList<>();
        LocalDate day = LocalDate.of(2026, 11, 2);
        for (int i = 0; i < count; i++) {
            bars.add(new DailyBar(day.plusDays(i), new BigDecimal("100"), new BigDecimal("101"),
                    new BigDecimal("99"), new BigDecimal("100"), 1_000_000));
        }
        return bars;
    }

    @Test
    void fifteenBarsAreEnough() {
        // 14 true ranges of 2 => ATR 2 => 2.00% of 100
        assertEquals(0, ScreenMetrics.atrPercent(flatBars(15), LAST).compareTo(new BigDecimal("2.00")));
    }

    @Test
    void fewerThanFifteenBarsIsAnErrorNamingTheCount() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ScreenMetrics.atrPercent(flatBars(14), LAST));
        assertEquals("no ATR (14 bars)", e.getMessage());
    }

    @Test
    void noBarsIsAnError() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ScreenMetrics.atrPercent(List.of(), LAST));
        assertEquals("no ATR (0 bars)", e.getMessage());
    }

    @Test
    void zeroOrMissingLastPriceIsAnError() {
        assertThrows(IllegalArgumentException.class, () -> ScreenMetrics.atrPercent(flatBars(15), BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> ScreenMetrics.atrPercent(flatBars(15), null));
    }
}
