package io.github.signalbot.screen;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** Pure screen metrics: ATR% (simple mean of true range), ADV in millions, PASS rule. */
public final class ScreenMetrics {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);
    private static final BigDecimal ADV_MIN = BigDecimal.valueOf(5);
    private static final BigDecimal ATR_MIN = new BigDecimal("2.0");
    private static final BigDecimal ATR_MAX = new BigDecimal("4.0");

    private ScreenMetrics() {
    }

    /** ATR as a percent of {@code last}: simple mean of daily true range over the bars. */
    public static BigDecimal atrPercent(List<Bar> bars, BigDecimal last) {
        if (bars == null || bars.size() < 2 || last == null || last.signum() == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal sumTr = BigDecimal.ZERO;
        for (int i = 1; i < bars.size(); i++) {
            BigDecimal prevClose = bars.get(i - 1).close();
            Bar b = bars.get(i);
            BigDecimal tr = b.high().subtract(b.low())
                    .max(b.high().subtract(prevClose).abs())
                    .max(b.low().subtract(prevClose).abs());
            sumTr = sumTr.add(tr);
        }
        BigDecimal atr = sumTr.divide(BigDecimal.valueOf(bars.size() - 1), 6, RoundingMode.HALF_UP);
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
