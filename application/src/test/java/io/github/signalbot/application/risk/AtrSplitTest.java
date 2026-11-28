package io.github.signalbot.application.risk;

import io.github.signalbot.domain.model.DailyBar;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SB-041 / D-008: why daily bars must be split-adjusted. A hand-made series trades around 200 with a daily range of
 * 4 (high = close + 2, low = close - 2) and a 2-for-1 split after day 10: from day 11 the raw prices are halved.
 */
class AtrSplitTest {

    private static final LocalDate START = LocalDate.of(2026, 10, 1);
    private static final int DAYS = 20;
    private static final int SPLIT_DAY = 11;
    private static final BigDecimal TWO = new BigDecimal("2");

    /** The unsplit price on day i: 200 plus a small zig-zag, so the series is not flat. */
    private static BigDecimal unsplitClose(int i) {
        return new BigDecimal(200 + (i % 2 == 0 ? 1 : -1));
    }

    private static DailyBar bar(int i, BigDecimal close, BigDecimal halfRange) {
        BigDecimal high = close.add(halfRange);
        BigDecimal low = close.subtract(halfRange);
        return new DailyBar(START.plusDays(i), close, high, low, close, 1_000_000L);
    }

    /** What the company really traded at had there been no split. */
    private static List<DailyBar> unsplit() {
        List<DailyBar> bars = new ArrayList<>();
        for (int i = 0; i < DAYS; i++) {
            bars.add(bar(i, unsplitClose(i), TWO));
        }
        return bars;
    }

    /** Alpaca's default {@code adjustment=raw}: halved prices and range from the split day on. */
    private static List<DailyBar> raw() {
        List<DailyBar> bars = new ArrayList<>();
        for (int i = 0; i < DAYS; i++) {
            BigDecimal c = unsplitClose(i);
            bars.add(i < SPLIT_DAY ? bar(i, c, TWO) : bar(i, c.divide(TWO), BigDecimal.ONE));
        }
        return bars;
    }

    /** {@code adjustment=split}: every bar before the split is divided by 2, so the series is continuous. */
    private static List<DailyBar> adjusted() {
        List<DailyBar> bars = new ArrayList<>();
        for (int i = 0; i < DAYS; i++) {
            bars.add(bar(i, unsplitClose(i).divide(TWO), BigDecimal.ONE));
        }
        return bars;
    }

    @Test
    void rawBarsInflateAtrSeveralTimesOverAdjustedBars() {
        BigDecimal rawAtr = Atr.wilder(raw(), 14).orElseThrow();
        BigDecimal adjustedAtr = Atr.wilder(adjusted(), 14).orElseThrow();

        BigDecimal ratio = rawAtr.divide(adjustedAtr, 6, RoundingMode.HALF_UP);
        assertTrue(ratio.compareTo(new BigDecimal("3")) >= 0,
                "raw ATR " + rawAtr + " should be several times the adjusted ATR " + adjustedAtr);
    }

    @Test
    void adjustedBarsGiveTheAtrOfTheUnsplitSeriesInTodaysShares() {
        BigDecimal unsplitAtr = Atr.wilder(unsplit(), 14).orElseThrow();
        BigDecimal adjustedAtr = Atr.wilder(adjusted(), 14).orElseThrow();

        // Same shape, half the price: in today's (post-split) shares the ATR is exactly half the unsplit one.
        assertEquals(0, adjustedAtr.multiply(TWO).compareTo(unsplitAtr),
                "adjusted " + adjustedAtr + " x 2 vs unsplit " + unsplitAtr);
    }
}
