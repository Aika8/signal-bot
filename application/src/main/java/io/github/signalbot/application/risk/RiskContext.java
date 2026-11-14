package io.github.signalbot.application.risk;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The portfolio/account state a verdict is evaluated against. All monetary
 * values are in KZT. Assembled by the caller so the evaluator stays pure.
 *
 * @param executedTradesToday   trades already executed today
 * @param openPositionCount     number of distinct open positions
 * @param symbolHeld            whether the verdict's symbol is already held
 * @param symbolOpenedAt        when the held symbol was opened (null if not held)
 * @param totalValueKzt         total portfolio value
 * @param currentSymbolValueKzt current value held in the verdict's symbol
 * @param orderNotionalKzt      KZT notional this verdict would add (BUY)
 * @param dailyPnlPct           today's P&amp;L as a signed fraction (-0.04 == -4%)
 * @param now                   evaluation time
 * @param sellRealizesGain      for a SELL: whether it realises a gain (the 24h
 *                              holding rule applies only to gain-taking SELLs;
 *                              loss-cutting SELLs are always allowed)
 */
public record RiskContext(
        int executedTradesToday,
        int openPositionCount,
        boolean symbolHeld,
        Instant symbolOpenedAt,
        BigDecimal totalValueKzt,
        BigDecimal currentSymbolValueKzt,
        BigDecimal orderNotionalKzt,
        BigDecimal dailyPnlPct,
        Instant now,
        boolean sellRealizesGain
) {
}
