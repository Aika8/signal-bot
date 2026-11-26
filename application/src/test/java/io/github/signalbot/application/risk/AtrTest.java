package io.github.signalbot.application.risk;

import io.github.signalbot.domain.model.DailyBar;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AtrTest {

    private static final LocalDate START = LocalDate.of(2026, 11, 2);

    private static DailyBar bar(int day, String high, String low, String close) {
        BigDecimal h = new BigDecimal(high);
        BigDecimal l = new BigDecimal(low);
        return new DailyBar(START.plusDays(day), l, h, l, new BigDecimal(close), 1_000L);
    }

    private static Optional<BigDecimal> atr(String value) {
        return Optional.of(new BigDecimal(value));
    }

    /*
     * Hand-computed example, period 3, six bars (H / L / C):
     *
     *   b0  10.00 /  9.00 /  9.50   only gives prevClose 9.50
     *   b1  10.20 /  9.40 / 10.00   TR = max(0.80, |10.20-9.50|=0.70, |9.40-9.50|=0.10)   = 0.80
     *   b2  10.60 /  9.90 / 10.40   TR = max(0.70, |10.60-10.00|=0.60, |9.90-10.00|=0.10) = 0.70
     *   b3  10.50 /  9.80 / 10.10   TR = max(0.70, |10.50-10.40|=0.10, |9.80-10.40|=0.60) = 0.70
     *       first ATR = (0.80 + 0.70 + 0.70) / 3 = 2.20 / 3 = 0.7333333... -> 0.733333
     *   b4  11.30 / 10.90 / 11.20   TR = max(0.40, |11.30-10.10|=1.20, |10.90-10.10|=0.80) = 1.20 (gap up)
     *       ATR = (0.733333 * 2 + 1.20) / 3 = 2.666666 / 3 = 0.8888886... -> 0.888889
     *   b5  10.70 / 10.20 / 10.30   TR = max(0.50, |10.70-11.20|=0.50, |10.20-11.20|=1.00) = 1.00 (gap down)
     *       ATR = (0.888889 * 2 + 1.00) / 3 = 2.777778 / 3 = 0.925926 exactly
     */
    private static final List<DailyBar> SIX_BARS = List.of(
            bar(0, "10.00", "9.00", "9.50"),
            bar(1, "10.20", "9.40", "10.00"),
            bar(2, "10.60", "9.90", "10.40"),
            bar(3, "10.50", "9.80", "10.10"),
            bar(4, "11.30", "10.90", "11.20"),
            bar(5, "10.70", "10.20", "10.30"));

    @Test
    void smoothsWithWildersFormulaAfterTheFirstMean() {
        assertEquals(atr("0.925926"), Atr.wilder(SIX_BARS, 3));
    }

    @Test
    void exactlyPeriodPlusOneBarsGivesTheSimpleMeanOfTheFirstTrueRanges() {
        // b0..b3 above: first ATR = 2.20 / 3 = 0.733333
        assertEquals(atr("0.733333"), Atr.wilder(SIX_BARS.subList(0, 4), 3));
    }

    @Test
    void gapUpUsesHighMinusPreviousClose() {
        // prevClose 10.00; H 12.00, L 11.50: H-L = 0.50, |H-prevClose| = 2.00, |L-prevClose| = 1.50
        // period 1: ATR = 2.00 / 1 = 2.000000
        List<DailyBar> bars = List.of(
                bar(0, "10.20", "9.80", "10.00"),
                bar(1, "12.00", "11.50", "11.80"));
        assertEquals(atr("2.000000"), Atr.wilder(bars, 1));
    }

    @Test
    void gapDownUsesLowMinusPreviousClose() {
        // prevClose 10.00; H 9.00, L 8.60: H-L = 0.40, |H-prevClose| = 1.00, |L-prevClose| = 1.40
        // period 1: ATR = 1.40 / 1 = 1.400000
        List<DailyBar> bars = List.of(
                bar(0, "10.20", "9.80", "10.00"),
                bar(1, "9.00", "8.60", "8.80"));
        assertEquals(atr("1.400000"), Atr.wilder(bars, 1));
    }

    @Test
    void onlyPeriodBarsGivesEmpty() {
        assertEquals(Optional.empty(), Atr.wilder(SIX_BARS.subList(0, 3), 3));
    }

    @Test
    void noBarsGivesEmpty() {
        assertEquals(Optional.empty(), Atr.wilder(List.of(), 14));
    }

    @Test
    void periodBelowOneIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Atr.wilder(SIX_BARS, 0));
        assertThrows(IllegalArgumentException.class, () -> Atr.wilder(SIX_BARS, -1));
    }
}
