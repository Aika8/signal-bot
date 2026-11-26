package io.github.signalbot.domain.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DailyBarTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 20);

    private static BigDecimal p(String v) {
        return new BigDecimal(v);
    }

    @Test
    void acceptsValidBar() {
        DailyBar bar = new DailyBar(DAY, p("10.00"), p("11.50"), p("9.75"), p("11.00"), 1_000L);
        assertEquals(p("11.50"), bar.high());
    }

    @Test
    void acceptsFlatBarAndZeroVolume() {
        assertDoesNotThrow(() -> new DailyBar(DAY, p("10"), p("10"), p("10"), p("10"), 0L));
    }

    @Test
    void refusesHighBelowLow() {
        assertThrows(IllegalArgumentException.class,
                () -> new DailyBar(DAY, p("10"), p("9"), p("10"), p("9.5"), 1L));
    }

    @Test
    void refusesNegativePriceInEveryField() {
        assertThrows(IllegalArgumentException.class,
                () -> new DailyBar(DAY, p("-1"), p("10"), p("9"), p("9.5"), 1L));
        assertThrows(IllegalArgumentException.class,
                () -> new DailyBar(DAY, p("10"), p("-1"), p("-2"), p("9.5"), 1L));
        assertThrows(IllegalArgumentException.class,
                () -> new DailyBar(DAY, p("10"), p("10"), p("-0.01"), p("9.5"), 1L));
        assertThrows(IllegalArgumentException.class,
                () -> new DailyBar(DAY, p("10"), p("10"), p("9"), p("-9.5"), 1L));
    }

    @Test
    void refusesNegativeVolume() {
        assertThrows(IllegalArgumentException.class,
                () -> new DailyBar(DAY, p("10"), p("10"), p("9"), p("9.5"), -1L));
    }

    @Test
    void refusesNullInEveryField() {
        assertThrows(IllegalArgumentException.class,
                () -> new DailyBar(null, p("10"), p("10"), p("9"), p("9.5"), 1L));
        assertThrows(IllegalArgumentException.class,
                () -> new DailyBar(DAY, null, p("10"), p("9"), p("9.5"), 1L));
        assertThrows(IllegalArgumentException.class,
                () -> new DailyBar(DAY, p("10"), null, p("9"), p("9.5"), 1L));
        assertThrows(IllegalArgumentException.class,
                () -> new DailyBar(DAY, p("10"), p("10"), null, p("9.5"), 1L));
        assertThrows(IllegalArgumentException.class,
                () -> new DailyBar(DAY, p("10"), p("10"), p("9"), null, 1L));
    }
}
