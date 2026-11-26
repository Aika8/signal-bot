package io.github.signalbot.application.risk;

import io.github.signalbot.domain.model.DailyBar;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

/**
 * Average True Range with Wilder's smoothing (D-002: ATR(14) on daily bars).
 * The one place the ATR rule lives.
 */
public final class Atr {

    private static final int SCALE = 6;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private Atr() {
    }

    /**
     * Wilder's ATR after the last bar.
     *
     * <p>True range of bar i (i &ge; 1) = max(high &minus; low, |high &minus; prevClose|, |low &minus; prevClose|);
     * the first bar only supplies {@code prevClose}. The first ATR is the simple mean of the first {@code period}
     * true ranges; every later bar gives {@code ATR = (ATR_prev × (period − 1) + TR) / period}. Each division is
     * rounded to scale 6, {@link RoundingMode#HALF_UP}.
     *
     * @param bars   completed daily bars, oldest first
     * @param period smoothing period, at least 1
     * @return the ATR, or empty when there are fewer than {@code period + 1} bars (no ATR is never a guess)
     * @throws IllegalArgumentException if {@code period < 1}
     */
    public static Optional<BigDecimal> wilder(List<DailyBar> bars, int period) {
        if (period < 1) {
            throw new IllegalArgumentException("period must be at least 1, was " + period);
        }
        if (bars.size() < period + 1) {
            return Optional.empty();
        }
        BigDecimal periodValue = BigDecimal.valueOf(period);

        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 1; i <= period; i++) {
            sum = sum.add(trueRange(bars.get(i - 1), bars.get(i)));
        }
        BigDecimal atr = sum.divide(periodValue, SCALE, ROUNDING);

        BigDecimal previousWeight = BigDecimal.valueOf(period - 1L);
        for (int i = period + 1; i < bars.size(); i++) {
            BigDecimal tr = trueRange(bars.get(i - 1), bars.get(i));
            atr = atr.multiply(previousWeight).add(tr).divide(periodValue, SCALE, ROUNDING);
        }
        return Optional.of(atr);
    }

    private static BigDecimal trueRange(DailyBar previous, DailyBar bar) {
        BigDecimal prevClose = previous.close();
        BigDecimal highLow = bar.high().subtract(bar.low());
        BigDecimal highGap = bar.high().subtract(prevClose).abs();
        BigDecimal lowGap = bar.low().subtract(prevClose).abs();
        return highLow.max(highGap).max(lowGap);
    }
}
