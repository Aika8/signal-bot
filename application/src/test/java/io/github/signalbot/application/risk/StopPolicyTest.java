package io.github.signalbot.application.risk;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StopPolicyTest {

    // entry 100, ATR 2: band is [97, 94] (distance 3..6), ATR stop = 96
    private static final BigDecimal ENTRY = new BigDecimal("100");
    private static final Optional<BigDecimal> ATR = Optional.of(new BigDecimal("2"));
    private static final BigDecimal ATR_STOP = new BigDecimal("96");

    private static StopDecision decide(String claudeStop) {
        BigDecimal stop = claudeStop == null ? null : new BigDecimal(claudeStop);
        return StopPolicy.forBuy(ENTRY, stop, ATR).orElseThrow();
    }

    private static void assertAccepted(String claudeStop) {
        StopDecision d = decide(claudeStop);
        assertEquals(StopSource.CLAUDE, d.source());
        assertEquals(0, new BigDecimal(claudeStop).compareTo(d.stop()));
        assertEquals(0, new BigDecimal(claudeStop).compareTo(d.claudeStop()));
        assertEquals(0, new BigDecimal("2").compareTo(d.atr()));
    }

    private static void assertReplaced(String claudeStop) {
        StopDecision d = decide(claudeStop);
        assertEquals(StopSource.ATR, d.source());
        assertEquals(0, ATR_STOP.compareTo(d.stop()), () -> "stop was " + d.stop());
        if (claudeStop == null) {
            assertNull(d.claudeStop());
        } else {
            assertEquals(0, new BigDecimal(claudeStop).compareTo(d.claudeStop()));
        }
        assertEquals(0, new BigDecimal("2").compareTo(d.atr()));
    }

    @Test
    void startingValuesAreThoseOfD002() {
        assertEquals(0, new BigDecimal("2").compareTo(StopPolicy.K));
        assertEquals(0, new BigDecimal("1.5").compareTo(StopPolicy.MIN_ATR));
        assertEquals(0, new BigDecimal("3").compareTo(StopPolicy.MAX_ATR));
    }

    @Test
    void stopAtExactly1_5AtrIsAccepted() {
        assertAccepted("97");
    }

    @Test
    void stopAtExactly3AtrIsAccepted() {
        assertAccepted("94");
    }

    @Test
    void stopInsideTheBandIsAccepted() {
        assertAccepted("95.5");
    }

    @Test
    void stopAt1_49AtrIsReplaced() {
        assertReplaced("97.02");
    }

    @Test
    void stopAt3_01AtrIsReplaced() {
        assertReplaced("93.98");
    }

    @Test
    void missingStopIsReplaced() {
        assertReplaced(null);
    }

    @Test
    void stopAtEntryIsReplaced() {
        assertReplaced("100");
    }

    @Test
    void stopAboveEntryIsReplaced() {
        assertReplaced("104");
    }

    @Test
    void noAtrGivesNoDecision() {
        assertTrue(StopPolicy.forBuy(ENTRY, new BigDecimal("97"), Optional.empty()).isEmpty());
        assertTrue(StopPolicy.forBuy(ENTRY, null, Optional.empty()).isEmpty());
    }

    @Test
    void zeroAtrGivesNoDecision() {
        assertTrue(StopPolicy.forBuy(ENTRY, new BigDecimal("97"), Optional.of(BigDecimal.ZERO)).isEmpty());
    }

    @Test
    void fractionalAtrKeepsPrecision() {
        StopDecision d = StopPolicy.forBuy(new BigDecimal("50.25"), null, Optional.of(new BigDecimal("1.234567")))
                .orElseThrow();
        assertEquals(StopSource.ATR, d.source());
        assertEquals(0, new BigDecimal("47.780866").compareTo(d.stop()));
    }

    @Test
    void nullEntryIsAProgrammingError() {
        assertThrows(NullPointerException.class, () -> StopPolicy.forBuy(null, null, ATR));
    }
}
