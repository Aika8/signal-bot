package io.github.signalbot.domain.port;

import io.github.signalbot.domain.model.EarningsEvent;

import java.time.LocalDate;
import java.util.List;

/** Returns upcoming earnings events for a symbol within a date window. */
public interface EarningsCalendar {

    List<EarningsEvent> upcoming(String symbol, LocalDate from, LocalDate to);
}
