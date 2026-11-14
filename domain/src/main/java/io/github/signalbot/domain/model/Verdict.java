package io.github.signalbot.domain.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A trading verdict produced by the decision engine.
 *
 * @param symbol      instrument symbol
 * @param action      BUY / SELL / HOLD (default answer is HOLD)
 * @param quantity    suggested units (zero for HOLD)
 * @param entry       suggested entry price, may be null for HOLD
 * @param stop        suggested stop-loss price, may be null
 * @param takeProfit  suggested take-profit price, may be null
 * @param confidence  model confidence in [0, 1]
 * @param rationale   short human-readable explanation
 * @param expiresAt   after this instant the verdict is stale
 */
public record Verdict(
        String symbol,
        Action action,
        BigDecimal quantity,
        BigDecimal entry,
        BigDecimal stop,
        BigDecimal takeProfit,
        double confidence,
        String rationale,
        Instant expiresAt
) {
    public boolean isTrade() {
        return action == Action.BUY || action == Action.SELL;
    }
}
