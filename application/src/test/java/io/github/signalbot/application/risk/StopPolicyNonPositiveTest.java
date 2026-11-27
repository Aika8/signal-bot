package io.github.signalbot.application.risk;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** D-007 (SB-031): a stop at or below zero is never a usable stop. */
class StopPolicyNonPositiveTest {

    private static Optional<StopDecision> forBuy(String entry, String claudeStop, String atr) {
        return StopPolicy.forBuy(new BigDecimal(entry), claudeStop == null ? null : new BigDecimal(claudeStop),
                Optional.of(new BigDecimal(atr)));
    }

    @Test
    void atrStopBelowZeroWithoutClaudeStopIsEmpty() {
        // 3 − 2 × 2 = −1
        assertTrue(forBuy("3", null, "2").isEmpty());
    }

    @Test
    void atrStopExactlyZeroIsEmpty() {
        // 4 − 2 × 2 = 0
        assertTrue(forBuy("4", null, "2").isEmpty());
    }

    @Test
    void claudeStopOfZeroInsideTheBandIsReplacedByTheAtrStop() {
        // distance 3 is inside [1.8, 3.6] for ATR 1.2, but a stop of 0 is unusable → 3 − 2 × 1.2 = 0.6
        StopDecision d = forBuy("3", "0", "1.2").orElseThrow();
        assertEquals(StopSource.ATR, d.source());
        assertEquals(0, new BigDecimal("0.6").compareTo(d.stop()), () -> "stop was " + d.stop());
        assertEquals(0, BigDecimal.ZERO.compareTo(d.claudeStop()));
        assertEquals(0, new BigDecimal("1.2").compareTo(d.atr()));
    }

    @Test
    void negativeClaudeStopIsReplacedByTheAtrStop() {
        // distance 3.1 for ATR 1.2 is inside the band; −0.1 is unusable → 0.6
        StopDecision d = forBuy("3", "-0.1", "1.2").orElseThrow();
        assertEquals(StopSource.ATR, d.source());
        assertEquals(0, new BigDecimal("0.6").compareTo(d.stop()));
    }

    @Test
    void claudeStopOfZeroWithAnAtrStopBelowZeroIsEmpty() {
        // Claude's 0 is inside [3, 6] for ATR 2 but unusable; the ATR stop 3 − 4 = −1 is unusable too
        assertTrue(forBuy("3", "0", "2").isEmpty());
    }

    @Test
    void positiveAtrStopIsUnchanged() {
        StopDecision d = forBuy("10", null, "2").orElseThrow();
        assertEquals(StopSource.ATR, d.source());
        assertEquals(0, new BigDecimal("6").compareTo(d.stop()));
    }
}
