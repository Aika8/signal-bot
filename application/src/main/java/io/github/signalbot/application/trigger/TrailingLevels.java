package io.github.signalbot.application.trigger;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * The three parameters of a trailing take-profit (D-004), all in the instrument's price currency.
 *
 * @param activation price at which trailing starts (D-004: {@code entry + 2R})
 * @param offset     pullback from the best price that fires the exit (D-004: {@code 1 × ATR(14)})
 * @param spread     protective spread: suggested limit = breakout price − spread (D-004: {@code 0.1 × ATR(14)})
 */
public record TrailingLevels(BigDecimal activation, BigDecimal offset, BigDecimal spread) {

    public TrailingLevels {
        Objects.requireNonNull(activation, "activation");
        Objects.requireNonNull(offset, "offset");
        Objects.requireNonNull(spread, "spread");
        if (offset.signum() < 0) {
            throw new IllegalArgumentException("offset must not be negative: " + offset);
        }
        if (spread.signum() < 0) {
            throw new IllegalArgumentException("spread must not be negative: " + spread);
        }
    }
}
