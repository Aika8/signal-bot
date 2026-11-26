package io.github.signalbot.application.risk;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/**
 * The stop rule of D-002: Claude's stop is accepted when its distance below the entry is within
 * {@code [MIN_ATR, MAX_ATR] × ATR}; otherwise (missing, too tight, too wide, at or above the entry) it is replaced by
 * {@code entry − K × ATR}. Pure: no I/O, no state. The one place the stop rule lives.
 */
public final class StopPolicy {

    /** Multiple of ATR for the replacement stop. Starting value, D-002/D-005: never tuned by hand. */
    static final BigDecimal K = new BigDecimal("2");
    /** Tightest accepted distance of Claude's stop, in ATR. Starting value, D-002/D-005: never tuned by hand. */
    static final BigDecimal MIN_ATR = new BigDecimal("1.5");
    /** Widest accepted distance of Claude's stop, in ATR. Starting value, D-002/D-005: never tuned by hand. */
    static final BigDecimal MAX_ATR = new BigDecimal("3");

    private StopPolicy() {
    }

    /**
     * Decides the stop of a BUY.
     *
     * @param entry      the entry price
     * @param claudeStop Claude's stop, or {@code null} when it gave none
     * @param atr        ATR(14) of the instrument; empty when there is none
     * @return the decision, or empty when there is no usable ATR (empty, zero or negative) — the caller rejects the
     *         BUY with "no ATR" (D-002, no silent fallback)
     */
    public static Optional<StopDecision> forBuy(BigDecimal entry, BigDecimal claudeStop, Optional<BigDecimal> atr) {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(atr, "atr");
        if (atr.isEmpty() || atr.get().signum() <= 0) {
            return Optional.empty();
        }
        BigDecimal atrValue = atr.get();
        if (claudeStop != null && withinBand(entry.subtract(claudeStop), atrValue)) {
            return Optional.of(new StopDecision(claudeStop, StopSource.CLAUDE, claudeStop, atrValue));
        }
        BigDecimal atrStop = entry.subtract(K.multiply(atrValue));
        return Optional.of(new StopDecision(atrStop, StopSource.ATR, claudeStop, atrValue));
    }

    private static boolean withinBand(BigDecimal distance, BigDecimal atr) {
        return distance.signum() > 0
                && distance.compareTo(MIN_ATR.multiply(atr)) >= 0
                && distance.compareTo(MAX_ATR.multiply(atr)) <= 0;
    }
}
