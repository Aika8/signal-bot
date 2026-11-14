package io.github.signalbot.domain.port;

import io.github.signalbot.domain.model.EarningsEvent;

import java.time.LocalDate;
import java.util.List;

/**
 * Returns upcoming earnings events for a symbol within a date window.
 * An empty list means "the source answered: no events"; a failed or malformed
 * response throws {@link SourceFailureException}.
 */
public interface EarningsCalendar {

    List<EarningsEvent> upcoming(String symbol, LocalDate from, LocalDate to);
}
