package io.github.signalbot.domain.model;

import java.time.LocalDate;

/**
 * A scheduled earnings report.
 *
 * @param symbol instrument symbol
 * @param date   the reporting date
 */
public record EarningsEvent(
        String symbol,
        LocalDate date
) {
}
