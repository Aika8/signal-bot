package io.github.signalbot.application.lab;

import org.junit.jupiter.api.Test;

import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SB-504: sample statistics of the lab, checked against hand-computed values. */
class SampleStatsTest {

    private static final double EPS = 1e-12;

    @Test
    void oneToFiveMatchesTheHandComputedValues() {
        // n = 5; mean = (1+2+3+4+5)/5 = 15/5 = 3
        // deviations -2,-1,0,1,2 -> squares 4,1,0,1,4 = 10; sample variance 10/(5-1) = 2.5; std = sqrt(2.5) ≈ 1.5811
        // t = 3 / (sqrt(2.5)/sqrt(5)) = 3 / sqrt(0.5) = 3·sqrt(2) ≈ 4.2426
        SampleStats s = SampleStats.of(new double[] {1, 2, 3, 4, 5});

        assertEquals(5, s.n());
        assertEquals(3.0, s.mean(), EPS);
        assertEquals(Math.sqrt(2.5), s.std(), EPS);
        assertTrue(s.t().isPresent());
        assertEquals(3.0 / (Math.sqrt(2.5) / Math.sqrt(5)), s.t().getAsDouble(), EPS);
        assertEquals(3.0 * Math.sqrt(2.0), s.t().getAsDouble(), EPS);
    }

    @Test
    void negativeMeanGivesNegativeT() {
        // [-1, -3]: mean -2; deviations 1,-1 -> squares 2; variance 2/1 = 2; std = sqrt(2)
        // t = -2 / (sqrt(2)/sqrt(2)) = -2
        SampleStats s = SampleStats.of(new double[] {-1, -3});

        assertEquals(2, s.n());
        assertEquals(-2.0, s.mean(), EPS);
        assertEquals(Math.sqrt(2.0), s.std(), EPS);
        assertEquals(-2.0, s.t().getAsDouble(), EPS);
    }

    @Test
    void noValuesGiveAnEmptyT() {
        SampleStats s = SampleStats.of(new double[0]);

        assertEquals(0, s.n());
        assertEquals(0.0, s.mean());
        assertEquals(0.0, s.std());
        assertEquals(OptionalDouble.empty(), s.t());
    }

    @Test
    void oneValueGivesAnEmptyT() {
        SampleStats s = SampleStats.of(new double[] {0.7});

        assertEquals(1, s.n());
        assertEquals(0.7, s.mean());
        assertEquals(0.0, s.std());
        assertFalse(s.t().isPresent());
    }

    @Test
    void zeroSpreadGivesAnEmptyT() {
        // 0.1 three times: 0.1+0.1+0.1 = 0.30000000000000004 in binary, so a computed std would not be exactly 0;
        // zero spread is decided on the values.
        SampleStats s = SampleStats.of(new double[] {0.1, 0.1, 0.1});

        assertEquals(3, s.n());
        assertEquals(0.1, s.mean());
        assertEquals(0.0, s.std());
        assertFalse(s.t().isPresent());
    }

    @Test
    void nanIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> SampleStats.of(new double[] {1, Double.NaN, 3}));
    }

    @Test
    void infiniteValuesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> SampleStats.of(new double[] {1, Double.POSITIVE_INFINITY}));
        assertThrows(IllegalArgumentException.class, () -> SampleStats.of(new double[] {Double.NEGATIVE_INFINITY, 1}));
    }

    @Test
    void nullIsRefused() {
        assertThrows(NullPointerException.class, () -> SampleStats.of(null));
    }

    @Test
    void theInputIsNotChanged() {
        double[] xs = {5, 1, 3};
        SampleStats.of(xs);

        assertEquals(5.0, xs[0]);
        assertEquals(1.0, xs[1]);
        assertEquals(3.0, xs[2]);
    }
}
