package io.github.signalbot.domain.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SB-036 (issue #9, D-001): open and close must lie within [low, high]. */
class DailyBarRangeTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 20);

    private static BigDecimal p(String v) {
        return new BigDecimal(v);
    }

    private static void assertRefused(String field, BigDecimal open, BigDecimal close) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new DailyBar(DAY, open, p("11"), p("9"), close, 1L));
        assertTrue(e.getMessage().startsWith(field + " "),
                "message should name " + field + ": " + e.getMessage());
    }

    @Test
    void refusesCloseAboveHigh() {
        assertRefused("close", p("10"), p("11.01"));
    }

    @Test
    void refusesCloseBelowLow() {
        assertRefused("close", p("10"), p("8.99"));
    }

    @Test
    void refusesOpenAboveHigh() {
        assertRefused("open", p("11.01"), p("10"));
    }

    @Test
    void refusesOpenBelowLow() {
        assertRefused("open", p("8.99"), p("10"));
    }

    @Test
    void acceptsOpenAtHighAndCloseAtLow() {
        assertDoesNotThrow(() -> new DailyBar(DAY, p("11"), p("11"), p("9"), p("9"), 1L));
    }

    @Test
    void acceptsOpenAtLowAndCloseAtHighWithDifferentScale() {
        assertDoesNotThrow(() -> new DailyBar(DAY, p("9.000"), p("11.0"), p("9"), p("11.00"), 1L));
    }
}
