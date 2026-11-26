package io.github.signalbot.domain.port;

import io.github.signalbot.domain.model.DailyBar;

import java.util.List;

/** Answers historical daily-bar queries (e.g. the input of ATR, D-002). */
public interface PriceHistory {

    /**
     * Returns the last {@code count} completed daily bars of a symbol, oldest
     * first. The current, still-open session is never included.
     *
     * <p>An empty list means only "the source answered: no bars". A failed or
     * malformed source throws {@link SourceFailureException} (D-001); it never
     * returns an empty or partial list in its place.
     *
     * @throws SourceFailureException if the source failed or answered malformed data
     */
    List<DailyBar> dailyBars(String symbol, int count);
}
