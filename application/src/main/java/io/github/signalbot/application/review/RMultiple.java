package io.github.signalbot.application.review;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * The realised result of a closed (or reduced) position in R (D-003: "realised result of each closed trade is
 * stored in R"). The one place this rule lives.
 *
 * <p><b>Definition.</b> {@code R = netResultKzt / riskKzt}, scale 4, {@link RoundingMode#HALF_UP}.
 * <ul>
 *   <li>{@code riskKzt} is 1R: {@code executions.risk_kzt} of the BUY execution that opened the position; with
 *       several BUYs, the sum of their 1R.</li>
 *   <li>{@code netResultKzt} (fees are inside 1R, D-003) = the paper wallet's realised P&amp;L of the SELL before
 *       fees ({@code realizedKzt}) &minus; this SELL's total fee &minus; the opening BUY executions' total fee
 *       &times; sold qty / bought qty (see {@link #netResultKzt}). Tax is not part of R.</li>
 *   <li>A loss beyond 1R is recorded as it is, never clipped (D-002 "Known limits": paper exits fill at the
 *       operator's price, not at the stop).</li>
 *   <li>No usable 1R ({@code riskKzt} null or &le; 0) gives empty: the trade is left out of R statistics, and
 *       the review says how many were left out.</li>
 * </ul>
 */
public final class RMultiple {

    private static final int SCALE = 4;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private RMultiple() {
    }

    /**
     * @param netResultKzt net result of the SELL in KZT (see the class Javadoc), not null
     * @param riskKzt      1R of the position in KZT, or null when unknown
     * @return the result in R, or empty when {@code riskKzt} is null or not positive
     */
    public static Optional<BigDecimal> of(BigDecimal netResultKzt, BigDecimal riskKzt) {
        if (netResultKzt == null) {
            throw new IllegalArgumentException("netResultKzt is required");
        }
        if (riskKzt == null || riskKzt.signum() <= 0) {
            return Optional.empty();
        }
        return Optional.of(netResultKzt.divide(riskKzt, SCALE, ROUNDING));
    }

    /**
     * Net result of a SELL in KZT: {@code realizedKzt − sellFeeKzt − buyFeesKzt × soldQty / boughtQty}; the
     * prorated BUY fees are rounded to scale 4, {@link RoundingMode#HALF_UP}.
     *
     * @param realizedKzt realised P&amp;L of the SELL before fees, as the paper wallet computed it
     * @param sellFeeKzt  total fee of this SELL
     * @param buyFeesKzt  total fee of the BUYs that opened the position
     * @param soldQty     quantity sold by this SELL
     * @param boughtQty   quantity bought by those BUYs, positive
     * @throws IllegalArgumentException if an argument is null or {@code boughtQty} is not positive
     */
    public static BigDecimal netResultKzt(BigDecimal realizedKzt, BigDecimal sellFeeKzt, BigDecimal buyFeesKzt,
                                          BigDecimal soldQty, BigDecimal boughtQty) {
        if (realizedKzt == null || sellFeeKzt == null || buyFeesKzt == null || soldQty == null
                || boughtQty == null) {
            throw new IllegalArgumentException("all amounts are required");
        }
        if (boughtQty.signum() <= 0) {
            throw new IllegalArgumentException("boughtQty must be positive, was " + boughtQty);
        }
        BigDecimal buyFeeShare = buyFeesKzt.multiply(soldQty).divide(boughtQty, SCALE, ROUNDING);
        return realizedKzt.subtract(sellFeeKzt).subtract(buyFeeShare);
    }
}
