package io.github.signalbot.application.trigger;

import java.math.BigDecimal;

/**
 * A fired protective exit.
 *
 * @param symbol         the position's symbol
 * @param type           EXIT_STOP, EXIT_TP or EXIT_TRAIL
 * @param triggerPrice   the price that breached the level (for EXIT_TRAIL: the breakout price)
 * @param level          the stop / take-profit level that was breached; for EXIT_TRAIL {@code best − offset}
 * @param suggestedLimit the limit price suggested to the operator, or {@code null} when there is none; for
 *                       EXIT_TRAIL {@code triggerPrice − spread} (D-004)
 */
public record ExitSignal(String symbol, ExitType type, BigDecimal triggerPrice, BigDecimal level,
                         BigDecimal suggestedLimit) {

    /** An exit without a suggested limit (stop, fixed take-profit). */
    public ExitSignal(String symbol, ExitType type, BigDecimal triggerPrice, BigDecimal level) {
        this(symbol, type, triggerPrice, level, null);
    }
}
