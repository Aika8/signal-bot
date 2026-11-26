package io.github.signalbot.application.risk;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * The stop a BUY will carry (D-002).
 *
 * @param stop       the stop that is used
 * @param source     where {@code stop} came from
 * @param claudeStop Claude's original stop, {@code null} when it gave none; kept so the operator sees it when replaced
 * @param atr        the ATR(14) the decision was made with
 */
public record StopDecision(BigDecimal stop, StopSource source, BigDecimal claudeStop, BigDecimal atr) {

    public StopDecision {
        Objects.requireNonNull(stop, "stop");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(atr, "atr");
    }
}
