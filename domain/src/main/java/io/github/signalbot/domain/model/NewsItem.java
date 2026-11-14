package io.github.signalbot.domain.model;

import java.time.Instant;

/** A news headline relevant to a watchlist symbol. */
public record NewsItem(
        String symbol,
        String headline,
        String source,
        Instant ts,
        String url
) {
}
