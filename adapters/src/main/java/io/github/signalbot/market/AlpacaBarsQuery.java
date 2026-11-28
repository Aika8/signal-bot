package io.github.signalbot.market;

import org.springframework.web.util.UriBuilder;

import java.net.URI;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * The one place that builds an Alpaca daily-bars request: {@code /v2/stocks/{symbol}/bars?timeframe=1Day}
 * with a look-back {@code start}, {@code limit = count + 1}, {@code sort=desc}, {@code adjustment=split} and
 * {@code feed=sip}.
 *
 * <p>D-008: bars are split-adjusted (Alpaca's default is {@code raw}, which turns a split into a huge true range and
 * inflates ATR(14), the stop and the size for weeks); {@code feed=sip} is Alpaca's default, sent explicitly so a plan
 * change cannot silently switch to IEX. No {@code end} is sent: Alpaca's default end (15 minutes before now without
 * real-time SIP access) keeps a free-plan SIP request valid.
 *
 * <p>Without {@code start} Alpaca returns only today's bar. {@code today} is the New York date: a bar dated
 * {@code today} may be the still-open session, so callers pass {@link #today()} to
 * {@link AlpacaBarsMapper#completedBars} to drop it and keep the last {@code count} completed bars.
 *
 * @param symbol the ticker
 * @param count  completed bars wanted, positive
 * @param today  the session date in America/New_York
 */
public record AlpacaBarsQuery(String symbol, int count, LocalDate today) {

    public static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    /** D-008: split-adjusted, not dividend-adjusted. */
    public static final String ADJUSTMENT = "split";

    /** D-008: Alpaca's default feed, named explicitly. */
    public static final String FEED = "sip";

    public AlpacaBarsQuery {
        if (count < 1) {
            throw new IllegalArgumentException("count must be positive: " + count);
        }
    }

    /** A query for the last {@code count} completed bars as of {@code clock}'s New York date. */
    public static AlpacaBarsQuery of(String symbol, int count, Clock clock) {
        return new AlpacaBarsQuery(symbol, count, LocalDate.now(clock.withZone(NEW_YORK)));
    }

    /** Calendar days back that surely hold {@code count} trading days (weekends, holidays). */
    public LocalDate start() {
        return today.minusDays(2L * count + 10);
    }

    /** The request URI on the Alpaca data client's base URL. */
    public URI uri(UriBuilder b) {
        return b.path("/v2/stocks/{s}/bars")
                .queryParam("timeframe", "1Day")
                .queryParam("start", start())
                .queryParam("limit", count + 1)
                .queryParam("sort", "desc")
                .queryParam("adjustment", ADJUSTMENT)
                .queryParam("feed", FEED)
                .build(symbol);
    }
}
