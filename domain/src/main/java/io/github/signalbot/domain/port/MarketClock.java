package io.github.signalbot.domain.port;

import io.github.signalbot.domain.model.Venue;

import java.time.Instant;

/** Answers whether a venue is open and when it next opens/closes. */
public interface MarketClock {

    boolean isOpen(Venue venue, Instant now);

    Instant nextOpen(Venue venue, Instant now);

    Instant nextClose(Venue venue, Instant now);
}
