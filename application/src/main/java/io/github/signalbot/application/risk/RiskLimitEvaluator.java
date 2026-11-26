package io.github.signalbot.application.risk;

import io.github.signalbot.domain.model.Action;
import io.github.signalbot.domain.model.Verdict;

import java.time.Instant;

/**
 * Pure evaluator of the hard risk limits. Returns the first violated limit, so
 * the rejection reason is deterministic. HOLD verdicts are always allowed.
 * Single-symbol concentration is not checked here: it caps the BUY size in
 * {@code PositionSizer} instead of rejecting (D-003).
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
}
