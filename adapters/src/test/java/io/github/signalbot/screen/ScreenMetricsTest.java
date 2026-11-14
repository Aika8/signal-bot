package io.github.signalbot.screen;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScreenMetricsTest {

    private static Bar bar(String high, String low, String close, long volume) {
        return new Bar(new BigDecimal(high), new BigDecimal(low), new BigDecimal(close), volume);
    }

    @Test
    void atrPercentIsMeanTrueRangeOverLast() {
        // TR(bar1)=4 (104-100), TR(bar2)=4 (103-99); mean=4; last=100 => 4.00%
        List<Bar> bars = List.of(
                bar("101", "99", "100", 1_000_000),
                bar("104", "100", "102", 1_000_000),
                bar("103", "99", "100", 1_000_000));
        assertEquals(0, ScreenMetrics.atrPercent(bars, new BigDecimal("100")).compareTo(new BigDecimal("4.00")));
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
