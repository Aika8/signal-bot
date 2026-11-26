package io.github.signalbot.application.review;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExpectancyTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    @Test
    void backlogExample() {
        Expectancy e = Expectancy.of(List.of(bd("2"), bd("1.5"), bd("-1"), bd("-1")));
        assertEquals(4, e.n());
        assertEquals(2, e.wins());
        assertEquals(2, e.losses());
        assertEquals(bd("0.5000"), e.winRate());
        assertEquals(bd("1.7500"), e.avgWin());
        assertEquals(bd("1.0000"), e.avgLoss());
        // 0.5 × 1.75 − 0.5 × 1.00 = 0.375
        assertEquals(bd("0.3750"), e.expectancy());
    }

    @Test
    void zeroRTradeCountsInNButNeitherWinNorLoss() {
        Expectancy e = Expectancy.of(List.of(bd("3"), bd("0.0000"), bd("-1")));
        assertEquals(3, e.n());
        assertEquals(1, e.wins());
        assertEquals(1, e.losses());
        assertEquals(bd("0.3333"), e.winRate());
        assertEquals(bd("3.0000"), e.avgWin());
        assertEquals(bd("1.0000"), e.avgLoss());
        // (3 − 1) / 3
        assertEquals(bd("0.6667"), e.expectancy());
    }

    @Test
    void onlyLossesGiveZeroAvgWinAndNegativeExpectancy() {
        Expectancy e = Expectancy.of(List.of(bd("-1.3"), bd("-0.7")));
        assertEquals(bd("0.0000"), e.winRate());
        assertEquals(bd("0.0000"), e.avgWin());
        assertEquals(bd("1.0000"), e.avgLoss());
        assertEquals(bd("-1.0000"), e.expectancy());
    }

    @Test
    void noTradesGiveZeros() {
        Expectancy e = Expectancy.of(List.of());
        assertEquals(0, e.n());
        assertEquals(bd("0.0000"), e.winRate());
        assertEquals(bd("0.0000"), e.expectancy());
    }

    @Test
    void expectancyMatchesFormulaWithUnevenCounts() {
        // wins 2, 1 ; losses −1, −1, −0.5 → P(win)=0.4, avgWin=1.5, P(loss)=0.6, avgLoss=0.8333…
        // E = 0.4×1.5 − 0.6×0.8333… = 0.6 − 0.5 = 0.1
        Expectancy e = Expectancy.of(List.of(bd("2"), bd("1"), bd("-1"), bd("-1"), bd("-0.5")));
        assertEquals(bd("0.8333"), e.avgLoss());
        assertEquals(bd("0.1000"), e.expectancy());
    }

    @Test
    void nullsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> Expectancy.of(null));
        assertThrows(IllegalArgumentException.class, () -> Expectancy.of(Arrays.asList(bd("1"), null)));
    }
}
