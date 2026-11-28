package io.github.signalbot.application.lab;

import java.util.OptionalDouble;

/**
 * Summary statistics of a sample (e.g. per-trade returns in R): size, mean, sample standard deviation (n - 1 in the
 * denominator) and the one-sample t statistic of the mean against zero, {@code t = mean / (std / sqrt(n))}.
 *
 * <p>The t statistic is empty when it is not defined: fewer than two values, or no spread (all values equal). With
 * no values the mean and std are 0; with one value the mean is that value and the std is 0. NaN or infinite values
 * are refused (they would silently poison every figure).
 *
 * <p>Pure lab code: no framework, no I/O.
 */
public record SampleStats(int n, double mean, double std, OptionalDouble t) {

    public SampleStats {
        if (n < 0) {
            throw new IllegalArgumentException("n must not be negative: " + n);
        }
        if (t == null) {
            throw new IllegalArgumentException("t must not be null (use OptionalDouble.empty())");
        }
    }

    /**
     * Computes the statistics of {@code xs} (two-pass: mean first, then the squared deviations from it).
     *
     * @throws NullPointerException     if {@code xs} is null
     * @throws IllegalArgumentException if a value is NaN or infinite
     */
    public static SampleStats of(double[] xs) {
        if (xs == null) {
            throw new NullPointerException("xs");
        }
        for (int i = 0; i < xs.length; i++) {
            if (!Double.isFinite(xs[i])) {
                throw new IllegalArgumentException("value " + i + " is not finite: " + xs[i]);
            }
        }
        int n = xs.length;
        if (n == 0) {
            return new SampleStats(0, 0.0, 0.0, OptionalDouble.empty());
        }
        double sum = 0.0;
        for (double x : xs) {
            sum += x;
        }
        double mean = sum / n;
        if (n < 2 || allEqual(xs)) {
            // All equal: report the value itself as the mean (sum / n can be off by an ulp) and an exact zero spread.
            return new SampleStats(n, xs[0], 0.0, OptionalDouble.empty());
        }
        double squares = 0.0;
        for (double x : xs) {
            double d = x - mean;
            squares += d * d;
        }
        double std = Math.sqrt(squares / (n - 1));
        double t = mean / (std / Math.sqrt(n));
        return new SampleStats(n, mean, std, OptionalDouble.of(t));
    }

    /** Zero spread is decided on the values, not on a computed std that rounding may leave at 1e-17. */
    private static boolean allEqual(double[] xs) {
        for (double x : xs) {
            if (x != xs[0]) {
                return false;
            }
        }
        return true;
    }
}
