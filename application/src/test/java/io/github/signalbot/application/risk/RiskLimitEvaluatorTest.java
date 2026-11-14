package io.github.signalbot.application.risk;

import io.github.signalbot.domain.model.Action;
import io.github.signalbot.domain.model.Verdict;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiskLimitEvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-10-23T10:00:00Z");
    private final RiskLimitEvaluator evaluator = new RiskLimitEvaluator(RiskLimits.defaults());

    private static Verdict verdict(Action action) {
        return new Verdict("AAPL", action, new BigDecimal("1"), null, null, null,
                0.7, "test", NOW.plus(Duration.ofMinutes(30)));
    }

    // total=1_000_000, no symbol held, flat P&L, nothing executed, sells realise a gain.
    private static RiskContext healthy() {
        return new RiskContext(
                0, 0, false, null,
                new BigDecimal("1000000"), BigDecimal.ZERO, new BigDecimal("100000"),
                BigDecimal.ZERO, NOW, true);
    }

    private static RiskContext with(RiskContext base,
                                    Integer executed, Integer openCount, Boolean held,
                                    Instant openedAt, BigDecimal symbolValue,
                                    BigDecimal notional, BigDecimal pnlPct, Boolean gain) {
        return new RiskContext(
                executed != null ? executed : base.executedTradesToday(),
                openCount != null ? openCount : base.openPositionCount(),
                held != null ? held : base.symbolHeld(),
                openedAt != null ? openedAt : base.symbolOpenedAt(),
                base.totalValueKzt(),
                symbolValue != null ? symbolValue : base.currentSymbolValueKzt(),
                notional != null ? notional : base.orderNotionalKzt(),
                pnlPct != null ? pnlPct : base.dailyPnlPct(),
                base.now(),
                gain != null ? gain : base.sellRealizesGain());
    }

    @Test
    void holdIsAlwaysAllowed() {
        RiskContext maxedOut = with(healthy(), 99, 99, false, null, null, null, new BigDecimal("-0.99"), null);
        assertTrue(evaluator.evaluate(verdict(Action.HOLD), maxedOut).allowed());
    }

    @Test
    void healthyBuyIsAllowed() {
        assertTrue(evaluator.evaluate(verdict(Action.BUY), healthy()).allowed());
    }

    @Test
    void dailyLossStopBlocksNewBuy() {
        RiskContext ctx = with(healthy(), null, null, null, null, null, null, new BigDecimal("-0.03"), null);
        RiskDecision decision = evaluator.evaluate(verdict(Action.BUY), ctx);
        assertFalse(decision.allowed());
        assertEquals(RejectionReason.DAILY_LOSS_STOP, decision.reason());
    }

    @Test
    void dailyLossStopDoesNotBlockSell() {
        RiskContext ctx = with(healthy(), null, null, true,
                NOW.minus(Duration.ofHours(48)), null, null, new BigDecimal("-0.05"), null);
        assertTrue(evaluator.evaluate(verdict(Action.SELL), ctx).allowed());
    }

    @Test
    void maxTradesPerDayBlocks() {
        RiskContext ctx = with(healthy(), 2, null, null, null, null, null, null, null);
        RiskDecision decision = evaluator.evaluate(verdict(Action.BUY), ctx);
        assertFalse(decision.allowed());
        assertEquals(RejectionReason.MAX_TRADES_PER_DAY, decision.reason());
    }

    @Test
    void minHoldingPeriodBlocksEarlyGainTakingSell() {
        RiskContext ctx = with(healthy(), null, 1, true,
                NOW.minus(Duration.ofHours(5)), null, null, null, true);
        RiskDecision decision = evaluator.evaluate(verdict(Action.SELL), ctx);
        assertFalse(decision.allowed());
        assertEquals(RejectionReason.MIN_HOLDING_PERIOD, decision.reason());
    }

    @Test
    void lossCuttingSellWithinHoldingPeriodIsAllowed() {
        RiskContext ctx = with(healthy(), null, 1, true,
                NOW.minus(Duration.ofHours(5)), null, null, null, false);
        assertTrue(evaluator.evaluate(verdict(Action.SELL), ctx).allowed());
    }

    @Test
    void gainTakingSellAllowedAfterHoldingPeriod() {
        RiskContext ctx = with(healthy(), null, 1, true,
                NOW.minus(Duration.ofHours(25)), null, null, null, true);
        assertTrue(evaluator.evaluate(verdict(Action.SELL), ctx).allowed());
    }

    @Test
    void maxOpenPositionsBlocksNewSymbol() {
        RiskContext ctx = with(healthy(), null, 5, false, null, null, null, null, null);
        RiskDecision decision = evaluator.evaluate(verdict(Action.BUY), ctx);
        assertFalse(decision.allowed());
        assertEquals(RejectionReason.MAX_OPEN_POSITIONS, decision.reason());
    }

    @Test
    void buyingIntoHeldSymbolIgnoresOpenPositionCap() {
        RiskContext ctx = with(healthy(), null, 5, true,
                NOW.minus(Duration.ofHours(48)), new BigDecimal("50000"), new BigDecimal("50000"), null, null);
        assertTrue(evaluator.evaluate(verdict(Action.BUY), ctx).allowed());
    }

    @Test
    void concentrationCapBlocksOversizedBuy() {
        RiskContext ctx = with(healthy(), null, 1, true,
                NOW.minus(Duration.ofHours(48)), new BigDecimal("200000"), new BigDecimal("150000"), null, null);
        RiskDecision decision = evaluator.evaluate(verdict(Action.BUY), ctx);
        assertFalse(decision.allowed());
        assertEquals(RejectionReason.SYMBOL_CONCENTRATION, decision.reason());
    }

    @Test
    void concentrationExactlyAtCapIsAllowed() {
        RiskContext ctx = with(healthy(), null, 1, true,
                NOW.minus(Duration.ofHours(48)), new BigDecimal("150000"), new BigDecimal("150000"), null, null);
        assertTrue(evaluator.evaluate(verdict(Action.BUY), ctx).allowed());
    }
}
