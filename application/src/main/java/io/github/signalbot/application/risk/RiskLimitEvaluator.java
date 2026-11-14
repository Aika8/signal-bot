package io.github.signalbot.application.risk;

import io.github.signalbot.domain.model.Action;
import io.github.signalbot.domain.model.Verdict;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Pure evaluator of the hard risk limits. Returns the first violated limit, so
 * the rejection reason is deterministic. HOLD verdicts are always allowed.
 */
public final class RiskLimitEvaluator {

    private final RiskLimits limits;

    public RiskLimitEvaluator(RiskLimits limits) {
        this.limits = limits;
    }

    public RiskDecision evaluate(Verdict verdict, RiskContext ctx) {
        Action action = verdict.action();
        if (action == Action.HOLD) {
            return RiskDecision.allow();
        }

        if (action == Action.BUY && breachesDailyLossStop(ctx)) {
            return RiskDecision.reject(RejectionReason.DAILY_LOSS_STOP,
                    "Daily loss stop hit: new BUYs blocked until next day");
        }

        if (ctx.executedTradesToday() >= limits.maxExecutedTradesPerDay()) {
            return RiskDecision.reject(RejectionReason.MAX_TRADES_PER_DAY,
                    "Max " + limits.maxExecutedTradesPerDay() + " executed trades per day reached");
        }

        if (action == Action.SELL && ctx.sellRealizesGain() && violatesMinHolding(ctx)) {
            return RiskDecision.reject(RejectionReason.MIN_HOLDING_PERIOD,
                    "Minimum holding period of " + limits.minHoldingPeriod().toHours()
                            + "h not met for a gain-taking sell");
        }

        if (action == Action.BUY && !ctx.symbolHeld()
                && ctx.openPositionCount() >= limits.maxOpenPositions()) {
            return RiskDecision.reject(RejectionReason.MAX_OPEN_POSITIONS,
                    "Max " + limits.maxOpenPositions() + " open positions reached");
        }

        if (action == Action.BUY && breachesConcentration(ctx)) {
            return RiskDecision.reject(RejectionReason.SYMBOL_CONCENTRATION,
                    "Single-symbol cap of " + limits.maxSingleSymbolPct() + " would be exceeded");
        }

        return RiskDecision.allow();
    }

    private boolean breachesDailyLossStop(RiskContext ctx) {
        return ctx.dailyPnlPct().compareTo(limits.dailyLossStopPct().negate()) <= 0;
    }

    private boolean violatesMinHolding(RiskContext ctx) {
        if (!ctx.symbolHeld() || ctx.symbolOpenedAt() == null) {
            return false;
        }
        Instant earliestSell = ctx.symbolOpenedAt().plus(limits.minHoldingPeriod());
        return ctx.now().isBefore(earliestSell);
    }

    private boolean breachesConcentration(RiskContext ctx) {
        BigDecimal total = ctx.totalValueKzt();
        if (total == null || total.signum() <= 0) {
            return false;
        }
        BigDecimal projected = ctx.currentSymbolValueKzt().add(ctx.orderNotionalKzt());
        BigDecimal cap = total.multiply(limits.maxSingleSymbolPct());
        return projected.compareTo(cap) > 0;
    }
}
