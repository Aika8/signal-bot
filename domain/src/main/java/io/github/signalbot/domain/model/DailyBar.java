package io.github.signalbot.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One completed daily bar (OHLCV) of an instrument.
 *
 * @param date   trading day the bar covers
 * @param open   first trade price of the day
 * @param high   highest trade price of the day
 * @param low    lowest trade price of the day
 * @param close  last trade price of the day
 * @param volume traded volume
 * @throws IllegalArgumentException if a field is null, a price is negative,
 *                                  {@code high < low}, {@code open} or {@code close}
 *                                  lies outside {@code [low, high]}, or the volume is negative
 */
public record DailyBar(
        LocalDate date,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        long volume
) {

    public DailyBar {
        requireNonNull(date, "date");
        requireNonNegative(open, "open");
        requireNonNegative(high, "high");
        requireNonNegative(low, "low");
        requireNonNegative(close, "close");
        if (high.compareTo(low) < 0) {
            throw new IllegalArgumentException("high " + high + " < low " + low + " on " + date);
        }
        requireWithinRange(open, "open", low, high, date);
        requireWithinRange(close, "close", low, high, date);
        if (volume < 0) {
            throw new IllegalArgumentException("negative volume " + volume + " on " + date);
        }
    }

    private static void requireNonNull(Object value, String field) {
        if (Objects.isNull(value)) {
            throw new IllegalArgumentException(field + " is null");
        }
    }

    private static void requireWithinRange(BigDecimal price, String field,
                                           BigDecimal low, BigDecimal high, LocalDate date) {
        if (price.compareTo(low) < 0 || price.compareTo(high) > 0) {
            throw new IllegalArgumentException(
                    field + " " + price + " outside [low " + low + ", high " + high + "] on " + date);
        }
    }

    private static void requireNonNegative(BigDecimal price, String field) {
        requireNonNull(price, field);
        if (price.signum() < 0) {
            throw new IllegalArgumentException("negative " + field + " " + price);
        }
    }
}
