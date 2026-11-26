package io.github.signalbot.screen;

import io.github.signalbot.application.risk.Atr;
import io.github.signalbot.domain.model.DailyBar;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** Pure screen metrics: ATR% (Wilder's ATR(14), D-002), ADV in millions, PASS rule. */
public final class ScreenMetrics {

    private static final int ATR_PERIOD = 14;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);
    private static final BigDecimal ADV_MIN = BigDecimal.valueOf(5);
    private static final BigDecimal ATR_MIN = new BigDecimal("2.0");
    private static final BigDecimal ATR_MAX = new BigDecimal("4.0");

    private ScreenMetrics() {
    }

    /**
     * ATR as a percent of {@code last}: Wilder's ATR(14) from {@link Atr#wilder} (D-002) / last × 100, scale 2.
     *
     * @param bars completed daily bars, oldest first
     * @throws IllegalStateException    with "no ATR (n bars)" when there are fewer than 15 bars
     * @throws IllegalArgumentException when {@code last} is missing or zero
     */
    public static BigDecimal atrPercent(List<DailyBar> bars, BigDecimal last) {
        if (last == null || last.signum() == 0) {
            throw new IllegalArgumentException("no last price for ATR%: " + last);
        }
        BigDecimal atr = Atr.wilder(bars, ATR_PERIOD)
                .orElseThrow(() -> new IllegalStateException("no ATR (" + bars.size() + " bars)"));
        return atr.divide(last, 6, RoundingMode.HALF_UP).multiply(HUNDRED).setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal advMillions(List<Bar> bars) {
        if (bars == null || bars.isEmpty()) {
            return BigDecimal.ZERO;
        }
        long sum = 0;
        for (Bar b : bars) {
            sum += b.volume();
        }
        return BigDecimal.valueOf(sum)
                .divide(BigDecimal.valueOf(bars.size()), 0, RoundingMode.HALF_UP)
                .divide(MILLION, 2, RoundingMode.HALF_UP);
    }

    public static boolean pass(BigDecimal price, BigDecimal advMillions, BigDecimal atrPercent, BigDecimal maxPrice) {
        return price.compareTo(maxPrice) <= 0
                && advMillions.compareTo(ADV_MIN) >= 0
                && atrPercent.compareTo(ATR_MIN) >= 0
                && atrPercent.compareTo(ATR_MAX) <= 0;
    }
}
