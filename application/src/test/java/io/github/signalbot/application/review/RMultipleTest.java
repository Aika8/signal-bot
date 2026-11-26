package io.github.signalbot.application.review;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RMultipleTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    @Test
    void lossBeyondOneRIsRecordedNotClipped() {
        // −13 000 / 10 000 = −1.3
        assertEquals(Optional.of(bd("-1.3000")), RMultiple.of(bd("-13000"), bd("10000")));
    }

    @Test
    void gainIsPositiveR() {
        // 25 000 / 10 000 = 2.5
        assertEquals(Optional.of(bd("2.5000")), RMultiple.of(bd("25000"), bd("10000")));
    }

    @Test
    void roundsHalfUpToScaleFour() {
        // 1 / 3 = 0.33333… → 0.3333; 2 / 3 = 0.66666… → 0.6667; 0.00005 / 1 → 0.0001
        assertEquals(Optional.of(bd("0.3333")), RMultiple.of(bd("1"), bd("3")));
        assertEquals(Optional.of(bd("0.6667")), RMultiple.of(bd("2"), bd("3")));
        assertEquals(Optional.of(bd("0.0001")), RMultiple.of(bd("0.00005"), bd("1")));
        assertEquals(Optional.of(bd("-0.0001")), RMultiple.of(bd("-0.00005"), bd("1")));
    }

    @Test
    void missingOrNonPositiveRiskLeavesTheTradeOut() {
        assertTrue(RMultiple.of(bd("-13000"), null).isEmpty());
        assertTrue(RMultiple.of(bd("-13000"), BigDecimal.ZERO).isEmpty());
        assertTrue(RMultiple.of(bd("-13000"), bd("-1")).isEmpty());
    }

    @Test
    void nullResultIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> RMultiple.of(null, bd("10000")));
    }

    @Test
    void netResultSubtractsSellFeeAndProratedBuyFees() {
        // realised 21 000, SELL fee 530.25, BUY fees 1 060.50 for 4 bought, 2 sold → 530.25
        // 21 000 − 530.25 − 530.25 = 19 939.50
        assertEquals(0, bd("19939.50").compareTo(
                RMultiple.netResultKzt(bd("21000"), bd("530.25"), bd("1060.50"), bd("2"), bd("4"))));
    }

    @Test
    void netResultOfAFullCloseTakesAllBuyFees() {
        // −12 000 − 500 − 500 = −13 000
        assertEquals(0, bd("-13000").compareTo(
                RMultiple.netResultKzt(bd("-12000"), bd("500"), bd("500"), bd("10"), bd("10"))));
    }

    @Test
    void proratedBuyFeeRoundsHalfUpToScaleFour() {
        // 100 × 1 / 3 = 33.3333… → 33.3333; 0 − 0 − 33.3333 = −33.3333
        assertEquals(0, bd("-33.3333").compareTo(
                RMultiple.netResultKzt(BigDecimal.ZERO, BigDecimal.ZERO, bd("100"), bd("1"), bd("3"))));
    }

    @Test
    void netResultRejectsNonPositiveBoughtQuantity() {
        assertThrows(IllegalArgumentException.class,
                () -> RMultiple.netResultKzt(bd("1"), bd("0"), bd("0"), bd("1"), BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> RMultiple.netResultKzt(null, bd("0"), bd("0"), bd("1"), bd("1")));
    }
}
