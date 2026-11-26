package io.github.signalbot.application.risk;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;

/**
 * Hard risk limits, enforced in code (never delegated to the prompt).
 *
 * @param maxExecutedTradesPerDay max executed trades (BUY or SELL) per day
 * @param minHoldingPeriod        minimum time before a holding may be sold
 * @param maxSingleSymbolPct      max fraction of total value in one symbol
 * @param maxOpenPositions        max number of distinct open positions
 * @param dailyLossStopPct        daily loss (as a positive fraction) that blocks new BUYs
 * @param riskPerTradePct         fraction of total value risked per trade, 1R (D-003); must be in
 *                                {@code (0, riskPerTradeCapPct]}
 * @param riskPerTradeCapPct      the cap on {@code riskPerTradePct} (D-003); must be in {@code (0, HARD_CAP_RISK_PER_TRADE_PCT]}
 */
public record RiskLimits(
        int maxExecutedTradesPerDay,
        Duration minHoldingPeriod,
        BigDecimal maxSingleSymbolPct,
        int maxOpenPositions,
        BigDecimal dailyLossStopPct,
        BigDecimal riskPerTradePct,
        BigDecimal riskPerTradeCapPct
) {
    /** Risk per trade, starting value of D-003 (D-005: never tuned by hand). */
    public static final BigDecimal DEFAULT_RISK_PER_TRADE_PCT = new BigDecimal("0.01");
    /** The hard cap of D-003 on the risk per trade. */
    public static final BigDecimal HARD_CAP_RISK_PER_TRADE_PCT = new BigDecimal("0.02");

    public RiskLimits {
        Objects.requireNonNull(riskPerTradePct, "riskPerTradePct");
        Objects.requireNonNull(riskPerTradeCapPct, "riskPerTradeCapPct");
        if (riskPerTradeCapPct.signum() <= 0 || riskPerTradeCapPct.compareTo(HARD_CAP_RISK_PER_TRADE_PCT) > 0) {
            throw new IllegalArgumentException(
                    "riskPerTradeCapPct must be in (0, " + HARD_CAP_RISK_PER_TRADE_PCT + "]: " + riskPerTradeCapPct);
        }
        if (riskPerTradePct.signum() <= 0 || riskPerTradePct.compareTo(riskPerTradeCapPct) > 0) {
            throw new IllegalArgumentException(
                    "riskPerTradePct must be in (0, " + riskPerTradeCapPct + "]: " + riskPerTradePct);
        }
    }

    /** The increment-1 limits; the risk per trade takes the D-003 values. */
    public RiskLimits(int maxExecutedTradesPerDay, Duration minHoldingPeriod, BigDecimal maxSingleSymbolPct,
                      int maxOpenPositions, BigDecimal dailyLossStopPct) {
        this(maxExecutedTradesPerDay, minHoldingPeriod, maxSingleSymbolPct, maxOpenPositions, dailyLossStopPct,
                DEFAULT_RISK_PER_TRADE_PCT, HARD_CAP_RISK_PER_TRADE_PCT);
    }

    /** The defaults from the increment-1 specification plus the risk per trade of D-003. */
    public static RiskLimits defaults() {
        return new RiskLimits(
                2,
                Duration.ofHours(24),
                new BigDecimal("0.30"),
                5,
                new BigDecimal("0.03"),
                DEFAULT_RISK_PER_TRADE_PCT,
                HARD_CAP_RISK_PER_TRADE_PCT);
    }
}
