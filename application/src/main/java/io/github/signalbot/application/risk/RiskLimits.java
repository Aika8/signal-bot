package io.github.signalbot.application.risk;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Hard risk limits, enforced in code (never delegated to the prompt).
 *
 * @param maxExecutedTradesPerDay max executed trades (BUY or SELL) per day
 * @param minHoldingPeriod        minimum time before a holding may be sold
 * @param maxSingleSymbolPct      max fraction of total value in one symbol
 * @param maxOpenPositions        max number of distinct open positions
 * @param dailyLossStopPct        daily loss (as a positive fraction) that blocks new BUYs
 */
public record RiskLimits(
        int maxExecutedTradesPerDay,
        Duration minHoldingPeriod,
        BigDecimal maxSingleSymbolPct,
        int maxOpenPositions,
        BigDecimal dailyLossStopPct
) {
    /** The defaults from the increment-1 specification. */
    public static RiskLimits defaults() {
        return new RiskLimits(
                2,
                Duration.ofHours(24),
                new BigDecimal("0.30"),
                5,
                new BigDecimal("0.03"));
    }
}
