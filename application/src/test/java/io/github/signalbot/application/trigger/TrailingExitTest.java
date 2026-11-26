package io.github.signalbot.application.trigger;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Trailing take-profit in {@link ExitGuard} (D-004). Entry 100, stop 96, ATR 2: activation 108, offset 2, spread 0.2. */
class TrailingExitTest {

    private static final TrailingLevels LEVELS = new TrailingLevels(bd("108"), bd("2"), bd("0.2"));

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    private static void assertBd(String expected, BigDecimal actual) {
        assertEquals(0, bd(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    private static ExitGuard armed() {
        ExitGuard guard = new ExitGuard();
        guard.armTrailing("AAPL", bd("96"), LEVELS);
        return guard;
    }

    @Test
    void noTrackingBeforeActivation() {
        ExitGuard guard = armed();
        assertEquals(new TrailingState(null, false), guard.state("AAPL").orElseThrow());

        assertTrue(guard.onPrice("AAPL", bd("107.99")).isEmpty());
        assertTrue(guard.onPrice("AAPL", bd("103")).isEmpty());        // a pullback before activation fires nothing
        TrailingState state = guard.state("AAPL").orElseThrow();
        assertFalse(state.activated());
        assertNull(state.best());
    }

    @Test
    void activatesAtTheActivationPrice() {
        ExitGuard guard = armed();
        assertTrue(guard.onPrice("AAPL", bd("108")).isEmpty());
        TrailingState state = guard.state("AAPL").orElseThrow();
        assertTrue(state.activated());
        assertBd("108", state.best());
    }

    @Test
    void bestRisesAndNeverFalls() {
        ExitGuard guard = armed();
        guard.onPrice("AAPL", bd("109"));
        guard.onPrice("AAPL", bd("112.5"));
        guard.onPrice("AAPL", bd("111"));                               // within the offset: no exit, best kept
        assertBd("112.5", guard.state("AAPL").orElseThrow().best());
    }

    @Test
    void exitsWhenPriceFallsBelowBestMinusOffset() {
        ExitGuard guard = armed();
        guard.onPrice("AAPL", bd("108"));
        guard.onPrice("AAPL", bd("112"));
        assertTrue(guard.onPrice("AAPL", bd("110")).isEmpty());        // equal to best − offset: no exit

        ExitSignal s = guard.onPrice("AAPL", bd("109.9")).orElseThrow();
        assertEquals("AAPL", s.symbol());
        assertEquals(ExitType.EXIT_TRAIL, s.type());
        assertBd("109.9", s.triggerPrice());
        assertBd("110", s.level());                                    // best − offset
        assertBd("109.7", s.suggestedLimit());                         // breakout price − spread
    }

    @Test
    void trailingExitFiresOnce() {
        ExitGuard guard = armed();
        guard.onPrice("AAPL", bd("112"));
        assertTrue(guard.onPrice("AAPL", bd("109")).isPresent());
        assertTrue(guard.onPrice("AAPL", bd("108")).isEmpty());
        assertTrue(guard.onPrice("AAPL", bd("115")).isEmpty());
        assertTrue(guard.onPrice("AAPL", bd("100")).isEmpty());
    }

    @Test
    void stopStillFiresAfterActivation() {
        ExitGuard guard = armed();
        guard.onPrice("AAPL", bd("108"));
        assertTrue(guard.state("AAPL").orElseThrow().activated());

        ExitSignal s = guard.onPrice("AAPL", bd("96")).orElseThrow();
        assertEquals(ExitType.EXIT_STOP, s.type());
        assertBd("96", s.level());
        assertNull(s.suggestedLimit());
    }

    @Test
    void gapThroughBothGivesTheStop() {
        ExitGuard guard = armed();
        guard.onPrice("AAPL", bd("120"));                              // trail level now 118
        ExitSignal s = guard.onPrice("AAPL", bd("90")).orElseThrow();  // below 118 and below the stop 96
        assertEquals(ExitType.EXIT_STOP, s.type());
        assertBd("90", s.triggerPrice());
        assertBd("96", s.level());
    }

    @Test
    void stateRoundTripsThroughRestore() {
        ExitGuard before = armed();
        before.onPrice("AAPL", bd("108"));
        before.onPrice("AAPL", bd("113"));
        TrailingState saved = before.state("AAPL").orElseThrow();

        ExitGuard after = new ExitGuard();                             // restart
        after.restore("AAPL", bd("96"), LEVELS, saved.best(), saved.activated());
        assertEquals(saved, after.state("AAPL").orElseThrow());

        assertTrue(after.onPrice("AAPL", bd("111.5")).isEmpty());      // 113 − 2 = 111: not below yet
        ExitSignal s = after.onPrice("AAPL", bd("110.9")).orElseThrow();
        assertEquals(ExitType.EXIT_TRAIL, s.type());
        assertBd("111", s.level());
        assertBd("110.7", s.suggestedLimit());
    }

    @Test
    void restoreBeforeActivationKeepsTrackingOff() {
        ExitGuard guard = new ExitGuard();
        guard.restore("AAPL", bd("96"), LEVELS, null, false);
        assertEquals(new TrailingState(null, false), guard.state("AAPL").orElseThrow());
        assertTrue(guard.onPrice("AAPL", bd("105")).isEmpty());
        assertFalse(guard.state("AAPL").orElseThrow().activated());
    }

    @Test
    void restoreRejectsInconsistentState() {
        ExitGuard guard = new ExitGuard();
        assertThrows(IllegalArgumentException.class, () -> guard.restore("AAPL", bd("96"), LEVELS, null, true));
        assertThrows(IllegalArgumentException.class,
                () -> guard.restore("AAPL", bd("96"), LEVELS, bd("110"), false));
    }

    @Test
    void reArmingResetsTheTrailingState() {
        ExitGuard guard = armed();
        guard.onPrice("AAPL", bd("115"));
        guard.armTrailing("AAPL", bd("96"), LEVELS);
        assertEquals(new TrailingState(null, false), guard.state("AAPL").orElseThrow());
    }

    @Test
    void noStateWithoutTrailing() {
        ExitGuard guard = new ExitGuard();
        assertTrue(guard.state("AAPL").isEmpty());
        guard.arm("AAPL", bd("96"), bd("120"));
        assertTrue(guard.state("AAPL").isEmpty());
        guard.armTrailing("MSFT", bd("96"), LEVELS);
        guard.disarm("MSFT");
        assertTrue(guard.state("MSFT").isEmpty());
    }

    @Test
    void fixedArmReplacesTrailing() {
        ExitGuard guard = armed();
        guard.arm("AAPL", bd("96"), null);
        guard.onPrice("AAPL", bd("120"));
        assertTrue(guard.onPrice("AAPL", bd("110")).isEmpty());        // no trailing any more
    }

    @Test
    void trailingLevelsRejectNegativeOffsetOrSpread() {
        assertThrows(IllegalArgumentException.class, () -> new TrailingLevels(bd("108"), bd("-1"), bd("0.2")));
        assertThrows(IllegalArgumentException.class, () -> new TrailingLevels(bd("108"), bd("2"), bd("-0.1")));
        assertThrows(NullPointerException.class, () -> new TrailingLevels(null, bd("2"), bd("0.2")));
    }

    @Test
    void stopAndFixedTakeProfitHaveNoSuggestedLimit() {
        ExitGuard guard = new ExitGuard();
        guard.arm("AAPL", bd("96"), bd("120"));
        assertNull(guard.onPrice("AAPL", bd("121")).orElseThrow().suggestedLimit());
    }
}
