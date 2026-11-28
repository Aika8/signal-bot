package io.github.signalbot.application.trigger;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** D-004 (SB-957): the trailing take-profit rule, moved from {@code ExecutionService} unchanged. */
class TrailingPolicyTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static void assertSame(String expected, BigDecimal actual) {
        assertEquals(0, bd(expected).compareTo(actual), () -> "expected " + expected + " but was " + actual);
    }

    @Test
    void levelsFollowTheStartingValuesOfD004() {
        // fill 100, stop 96 → R = 4; ATR 2.5
        TrailingLevels levels = TrailingPolicy.forFill(bd("100"), bd("96"), bd("2.5")).orElseThrow();

        assertSame("108", levels.activation());
        assertSame("2.5", levels.offset());
        assertSame("0.25", levels.spread());
    }

    @Test
    void levelsAreNotRounded() {
        TrailingLevels levels = TrailingPolicy.forFill(bd("10.0137"), bd("9.5021"), bd("0.3333")).orElseThrow();

        assertSame("11.0369", levels.activation());
        assertSame("0.3333", levels.offset());
        assertSame("0.03333", levels.spread());
    }

    @Test
    void factorsAreTheStartingValues() {
        assertSame("2", TrailingPolicy.ACTIVATION_R);
        assertSame("1", TrailingPolicy.OFFSET_ATR);
        assertSame("0.1", TrailingPolicy.SPREAD_ATR);
    }

    @Test
    void noAtrMeansStopOnly() {
        assertTrue(TrailingPolicy.forFill(bd("100"), bd("96"), null).isEmpty());
        assertTrue(TrailingPolicy.forFill(bd("100"), bd("96"), BigDecimal.ZERO).isEmpty());
        assertTrue(TrailingPolicy.forFill(bd("100"), bd("96"), bd("-1")).isEmpty());
    }

    @Test
    void noStopMeansStopOnly() {
        assertTrue(TrailingPolicy.forFill(bd("100"), null, bd("2")).isEmpty());
    }

    @Test
    void fillNotAboveTheStopMeansStopOnly() {
        assertTrue(TrailingPolicy.forFill(bd("96"), bd("96"), bd("2")).isEmpty());
        assertTrue(TrailingPolicy.forFill(bd("95"), bd("96"), bd("2")).isEmpty());
    }

    @Test
    void fillIsRequired() {
        assertThrows(NullPointerException.class, () -> TrailingPolicy.forFill(null, bd("96"), bd("2")));
    }

    @Test
    void emptyIsNotNull() {
        Optional<TrailingLevels> none = TrailingPolicy.forFill(bd("100"), null, null);
        assertTrue(none.isEmpty());
    }
}
