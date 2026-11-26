package io.github.signalbot.screen;

import io.github.signalbot.domain.model.DailyBar;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScreenMetricsTest {

    private static Bar bar(String high, String low, String close, long volume) {
        return new Bar(new BigDecimal(high), new BigDecimal(low), new BigDecimal(close), volume);
    }

    @Test
    void atrPercentIsWilderAtr14OverLast() {
        // 16 bars: TR(1..14)=2 (101-99) => first ATR = 2; TR(15)=16 (108-92) => ATR = (2*13 + 16)/14 = 3;
        // last=100 => 3.00% (a simple mean of the 15 TRs would give 2.93%)
        List<DailyBar> bars = new ArrayList<>();
        LocalDate day = LocalDate.of(2026, 11, 2);
        for (int i = 0; i < 15; i++) {
            bars.add(dailyBar(day.plusDays(i), "101", "99", "100"));
        }
        bars.add(dailyBar(day.plusDays(15), "108", "92", "100"));
        assertEquals(0, ScreenMetrics.atrPercent(bars, new BigDecimal("100")).compareTo(new BigDecimal("3.00")));
    }

    private static DailyBar dailyBar(LocalDate date, String high, String low, String close) {
        return new DailyBar(date, new BigDecimal(close), new BigDecimal(high), new BigDecimal(low),
                new BigDecimal(close), 1_000_000);
    }

    @Test
    void advMillionsAveragesVolume() {
        List<Bar> bars = List.of(bar("1", "1", "1", 4_000_000), bar("1", "1", "1", 6_000_000));
        assertEquals(0, ScreenMetrics.advMillions(bars).compareTo(new BigDecimal("5.00")));
    }

    @Test
    void passRequiresPriceAdvAndAtrBand() {
        BigDecimal max = new BigDecimal("250");
        assertTrue(ScreenMetrics.pass(new BigDecimal("100"), new BigDecimal("10"), new BigDecimal("3.0"), max));
        // boundaries are inclusive
        assertTrue(ScreenMetrics.pass(new BigDecimal("250"), new BigDecimal("5"), new BigDecimal("2.0"), max));
        assertTrue(ScreenMetrics.pass(new BigDecimal("100"), new BigDecimal("10"), new BigDecimal("4.0"), max));
    }

    @Test
    void failsOnEachBrokenCondition() {
        BigDecimal max = new BigDecimal("250");
        assertFalse(ScreenMetrics.pass(new BigDecimal("300"), new BigDecimal("10"), new BigDecimal("3.0"), max));
        assertFalse(ScreenMetrics.pass(new BigDecimal("100"), new BigDecimal("4"), new BigDecimal("3.0"), max));
        assertFalse(ScreenMetrics.pass(new BigDecimal("100"), new BigDecimal("10"), new BigDecimal("1.9"), max));
        assertFalse(ScreenMetrics.pass(new BigDecimal("100"), new BigDecimal("10"), new BigDecimal("4.1"), max));
    }
}
