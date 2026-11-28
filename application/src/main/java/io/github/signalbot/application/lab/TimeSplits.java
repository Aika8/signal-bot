package io.github.signalbot.application.lab;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Time splits for the lab: a holdout split by date and walk-forward windows.
 *
 * <p>Dates are calendar dates and every period is inclusive at both ends. Counts of days are calendar days;
 * trading days come from the data later, when a period is applied to a series of bars.
 */
public final class TimeSplits {

    private TimeSplits() {
    }

    /** An inclusive range of calendar dates. */
    public record Period(LocalDate start, LocalDate end) {
        public Period {
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            if (end.isBefore(start)) {
                throw new IllegalArgumentException("period ends " + end + " before it starts " + start);
            }
        }

        public boolean contains(LocalDate day) {
            return !day.isBefore(start) && !day.isAfter(end);
        }
    }

    /** A train part followed by a test part; the test part starts the day after the train part ends. */
    public record Split(Period train, Period test) {
        public Split {
            Objects.requireNonNull(train, "train");
            Objects.requireNonNull(test, "test");
            if (!test.start().isAfter(train.end())) {
                throw new IllegalArgumentException(
                        "test part starts " + test.start() + ", not after the train part ends " + train.end());
            }
        }
    }

    /**
     * Splits {@code start…end} at {@code holdoutStart}: train is {@code start…holdoutStart − 1}, the holdout (test)
     * is {@code holdoutStart…end}. The date itself belongs to the holdout. Both parts must hold at least one day.
     */
    public static Split holdout(LocalDate start, LocalDate end, LocalDate holdoutStart) {
        Objects.requireNonNull(holdoutStart, "holdoutStart");
        Period whole = new Period(start, end);
        if (!holdoutStart.isAfter(whole.start()) || holdoutStart.isAfter(whole.end())) {
            throw new IllegalArgumentException("holdout start " + holdoutStart + " must lie after " + start
                    + " and no later than " + end);
        }
        return new Split(new Period(start, holdoutStart.minusDays(1)), new Period(holdoutStart, end));
    }

    /**
     * Walk-forward windows over {@code start…end}: window i trains on {@code trainDays} days from
     * {@code start + i·stepDays}, then tests on the next {@code testDays} days. A window whose test part would end
     * after {@code end} is dropped, so every window is whole. {@code stepDays} must be at least {@code testDays},
     * so test parts never overlap.
     */
    public static List<Split> walkForward(LocalDate start, LocalDate end, int trainDays, int testDays,
                                          int stepDays) {
        Period whole = new Period(start, end);
        if (trainDays < 1 || testDays < 1 || stepDays < 1) {
            throw new IllegalArgumentException("train, test and step must be at least one day: train " + trainDays
                    + ", test " + testDays + ", step " + stepDays);
        }
        if (stepDays < testDays) {
            throw new IllegalArgumentException(
                    "step " + stepDays + " is shorter than the test part " + testDays + ": test parts would overlap");
        }
        List<Split> windows = new ArrayList<>();
        for (LocalDate trainStart = whole.start(); ; trainStart = trainStart.plusDays(stepDays)) {
            LocalDate trainEnd = trainStart.plusDays(trainDays - 1L);
            LocalDate testStart = trainEnd.plusDays(1);
            LocalDate testEnd = testStart.plusDays(testDays - 1L);
            if (testEnd.isAfter(whole.end())) {
                return List.copyOf(windows);
            }
            windows.add(new Split(new Period(trainStart, trainEnd), new Period(testStart, testEnd)));
        }
    }
}
