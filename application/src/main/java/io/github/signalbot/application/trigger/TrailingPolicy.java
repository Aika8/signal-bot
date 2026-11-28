package io.github.signalbot.application.trigger;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/**
 * The trailing take-profit rule of D-004: for a BUY filled at {@code fill} with stop {@code stop}, R = fill − stop,
 * activation = fill + 2R, offset = 1 × ATR(14), protective spread = 0.1 × ATR(14), unrounded. Pure: no I/O, no
 * state. The one place the D-004 factors live, shared by live execution and the backtest.
 */
public final class TrailingPolicy {

    /** Activation distance above the fill, in R. Starting value, D-004/D-005: never tuned by hand. */
    static final BigDecimal ACTIVATION_R = new BigDecimal("2");
    /** Pullback from the best price that fires the exit, in ATR. Starting value, D-004/D-005: never tuned by hand. */
    static final BigDecimal OFFSET_ATR = BigDecimal.ONE;
    /** Protective spread below the breakout price, in ATR. Starting value, D-004/D-005: never tuned by hand. */
    static final BigDecimal SPREAD_ATR = new BigDecimal("0.1");

    private TrailingPolicy() {
    }

    /**
     * The trailing levels of a BUY.
     *
     * @param fill the fill price
     * @param stop the position's stop, or {@code null} when it has none
     * @param atr  ATR(14) of the instrument, or {@code null} when there is none
     * @return the levels, or empty (stop only) when there is no ATR above zero, no stop, or the fill is not above the
     *         stop (no positive R)
     */
    public static Optional<TrailingLevels> forFill(BigDecimal fill, BigDecimal stop, BigDecimal atr) {
        Objects.requireNonNull(fill, "fill");
        if (atr == null || atr.signum() <= 0 || stop == null) {
            return Optional.empty();
        }
        BigDecimal r = fill.subtract(stop);
        if (r.signum() <= 0) {
            return Optional.empty();
        }
        return Optional.of(new TrailingLevels(fill.add(ACTIVATION_R.multiply(r)), OFFSET_ATR.multiply(atr),
                SPREAD_ATR.multiply(atr)));
    }
}
