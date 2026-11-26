package io.github.signalbot.application.risk;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PositionSizerTest {

    private final PositionSizer sizer = new PositionSizer(RiskLimits.defaults());

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, bd(expected).compareTo(actual), () -> "expected " + expected + " but was " + actual);
    }

    /*
     * Worked example of D-003, by hand:
     *   totalValue 10 000 000 KZT × 1 %           = 100 000 KZT risk budget
     *   (entry 100 − stop 96) USD × 500 KZT/USD   = 2 000 KZT risk per share
     *   (100 000 − 3 000 fees) / 2 000 = 48.5     → floor 48 shares
     *   min(48, Claude 100, concentration 60, cash 200) = 48, limited by RISK
     *   1R actually taken = 48 × 2 000            = 96 000 KZT
     */
    private SizeDecision example(OptionalLong claude, long concentration, long cash) {
        return sizer.size(bd("10000000"), bd("100"), bd("96"), bd("500"), bd("3000"), claude, concentration, cash);
    }

    @Test
    void workedExampleOfD003() {
        SizeDecision d = example(OptionalLong.of(100), 60, 200);

        assertEquals(48L, d.quantity());
        assertAmount("2000", d.riskPerShareKzt());
        assertAmount("96000", d.riskKzt());
        assertEquals(SizeDecision.RISK, d.limitedBy());
        assertNull(d.rejection());
        assertFalse(d.rejected());
    }

    @Test
    void noClaudeQuantityMeansNoClaudeBound() {
        SizeDecision d = example(OptionalLong.empty(), 60, 200);

        assertEquals(48L, d.quantity());
        assertEquals(SizeDecision.RISK, d.limitedBy());
    }

    @Test
    void claudeOnlyLowersTheSize() {
        SizeDecision d = example(OptionalLong.of(10), 60, 200);

        assertEquals(10, d.quantity());
        assertEquals(SizeDecision.CLAUDE, d.limitedBy());
        assertAmount("20000", d.riskKzt());
    }

    @Test
    void claudeEqualToTheRiskSizeIsNotReportedAsClaude() {
        SizeDecision d = example(OptionalLong.of(48), 60, 200);

        assertEquals(48L, d.quantity());
        assertEquals(SizeDecision.RISK, d.limitedBy());
    }

    @Test
    void concentrationCapBinds() {
        SizeDecision d = example(OptionalLong.of(100), 30, 200);

        assertEquals(30, d.quantity());
        assertEquals(SizeDecision.CONCENTRATION, d.limitedBy());
    }

    @Test
    void cashCapBinds() {
        SizeDecision d = example(OptionalLong.of(100), 60, 7);

        assertEquals(7, d.quantity());
        assertEquals(SizeDecision.CASH, d.limitedBy());
        assertAmount("14000", d.riskKzt());
    }

    @Test
    void feesEatTheWholeBudget() {
        SizeDecision d = sizer.size(bd("10000000"), bd("100"), bd("96"), bd("500"), bd("100000"),
                OptionalLong.of(100), 60, 200);

        assertEquals(0, d.quantity());
        assertAmount("0", d.riskKzt());
        assertEquals(SizeDecision.RISK, d.limitedBy());
        assertEquals("risk budget too small for this stop distance", d.rejection());
    }

    @Test
    void feesLeaveLessThanOneShare() {
        // (100 000 − 98 500) / 2 000 = 0.75 → 0
        SizeDecision d = sizer.size(bd("10000000"), bd("100"), bd("96"), bd("500"), bd("98500"),
                OptionalLong.of(100), 60, 200);

        assertEquals(0, d.quantity());
        assertEquals(PositionSizer.RISK_TOO_SMALL, d.rejection());
    }

    @Test
    void stopTooWideForTheBudgetIsRejected() {
        // risk per share (100 − 50) × 500 = 25 000; budget 10 000 → 0 shares
        SizeDecision d = sizer.size(bd("1000000"), bd("100"), bd("50"), bd("500"), bd("0"),
                OptionalLong.empty(), 60, 200);

        assertEquals(0, d.quantity());
        assertAmount("25000", d.riskPerShareKzt());
        assertEquals(PositionSizer.RISK_TOO_SMALL, d.rejection());
    }

    @Test
    void capThatMakesItZeroIsNamedInTheRejection() {
        assertEquals(PositionSizer.CLAUDE_TOO_SMALL, example(OptionalLong.of(0), 60, 200).rejection());
        assertEquals(PositionSizer.CONCENTRATION_TOO_SMALL, example(OptionalLong.of(100), 0, 200).rejection());
        assertEquals(PositionSizer.CASH_TOO_SMALL, example(OptionalLong.of(100), 60, 0).rejection());
        assertEquals(SizeDecision.CASH, example(OptionalLong.of(100), 60, 0).limitedBy());
    }

    @Test
    void entryAtOrBelowStopIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> sizer.size(bd("10000000"), bd("96"), bd("96"), bd("500"), bd("0"),
                OptionalLong.empty(), 60, 200));
        assertThrows(IllegalArgumentException.class, () -> sizer.size(bd("10000000"), bd("95"), bd("96"), bd("500"), bd("0"),
                OptionalLong.empty(), 60, 200));
    }

    @Test
    void usesTheConfiguredRiskPerTrade() {
        RiskLimits twoPct = new RiskLimits(2, Duration.ofHours(24), bd("0.30"), 5, bd("0.03"),
                bd("0.02"), bd("0.02"));
        // 200 000 − 3 000 = 197 000 / 2 000 = 98.5 → 98
        SizeDecision d = new PositionSizer(twoPct).size(bd("10000000"), bd("100"), bd("96"), bd("500"),
                bd("3000"), OptionalLong.empty(), 1000, 1000);

        assertEquals(98, d.quantity());
    }

    @Test
    void riskLimitsDefaultsCarryD003StartingValues() {
        RiskLimits d = RiskLimits.defaults();

        assertAmount("0.01", d.riskPerTradePct());
        assertAmount("0.02", d.riskPerTradeCapPct());
    }

    @Test
    void fiveArgumentConstructorKeepsWorking() {
        RiskLimits r = new RiskLimits(2, Duration.ofHours(24), bd("0.30"), 5, bd("0.03"));

        assertEquals(RiskLimits.defaults(), r);
    }

    @Test
    void riskPerTradeAboveTheCapIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new RiskLimits(2, Duration.ofHours(24), bd("0.30"), 5, bd("0.03"),
                bd("0.03"), bd("0.02")));
        assertThrows(IllegalArgumentException.class, () -> new RiskLimits(2, Duration.ofHours(24), bd("0.30"), 5, bd("0.03"),
                bd("0.02"), bd("0.015")));
        assertThrows(IllegalArgumentException.class, () -> new RiskLimits(2, Duration.ofHours(24), bd("0.30"), 5, bd("0.03"),
                bd("0.01"), bd("0.05")));
        assertThrows(IllegalArgumentException.class, () -> new RiskLimits(2, Duration.ofHours(24), bd("0.30"), 5, bd("0.03"),
                bd("0"), bd("0.02")));
    }
}
