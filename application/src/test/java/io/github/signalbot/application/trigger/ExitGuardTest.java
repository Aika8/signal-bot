package io.github.signalbot.application.trigger;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExitGuardTest {

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    @Test
    void stopFiresWhenPriceCrossesBetweenTwoTicks() {
        ExitGuard guard = new ExitGuard();
        guard.arm("AAPL", bd("100"), bd("120"));
        assertTrue(guard.onPrice("AAPL", bd("105")).isEmpty());          // above stop
        ExitSignal s = guard.onPrice("AAPL", bd("99")).orElseThrow();     // crossed below
        assertEquals(ExitType.EXIT_STOP, s.type());
        assertEquals(0, s.triggerPrice().compareTo(bd("99")));
    }

    @Test
    void stopFiresAtMostOncePerLevel() {
        ExitGuard guard = new ExitGuard();
        guard.arm("AAPL", bd("100"), null);
        assertTrue(guard.onPrice("AAPL", bd("99")).isPresent());
        assertTrue(guard.onPrice("AAPL", bd("98")).isEmpty());           // already fired
    }

    @Test
    void takeProfitFires() {
        ExitGuard guard = new ExitGuard();
        guard.arm("AAPL", bd("100"), bd("120"));
        assertTrue(guard.onPrice("AAPL", bd("119")).isEmpty());
        assertEquals(ExitType.EXIT_TP, guard.onPrice("AAPL", bd("121")).orElseThrow().type());
    }

    @Test
    void websocketGapCaughtBySnapshotStillFiresOnce() {
        // No intermediate ticks: a single snapshot far below the stop still fires exactly once.
        ExitGuard guard = new ExitGuard();
        guard.arm("AAPL", bd("100"), bd("120"));
        assertEquals(ExitType.EXIT_STOP, guard.onPrice("AAPL", bd("80")).orElseThrow().type());
        assertTrue(guard.onPrice("AAPL", bd("79")).isEmpty());
    }

    @Test
    void reArmsAfterNewVerdictUpdatesLevels() {
        ExitGuard guard = new ExitGuard();
        guard.arm("AAPL", bd("100"), null);
        assertTrue(guard.onPrice("AAPL", bd("99")).isPresent());
        guard.arm("AAPL", bd("95"), null);                               // new verdict re-arms
        assertTrue(guard.onPrice("AAPL", bd("94")).isPresent());
    }

    @Test
    void unarmedOrDisarmedSymbolNeverFires() {
        ExitGuard guard = new ExitGuard();
        assertTrue(guard.onPrice("AAPL", bd("1")).isEmpty());
        guard.arm("AAPL", bd("100"), null);
        guard.disarm("AAPL");
        assertTrue(guard.onPrice("AAPL", bd("1")).isEmpty());
    }
}
