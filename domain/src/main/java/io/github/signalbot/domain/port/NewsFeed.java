package io.github.signalbot.domain.port;

import io.github.signalbot.domain.model.NewsItem;

import java.time.Instant;
import java.util.List;

/**
 * Returns news for a symbol published since a given timestamp.
 * An empty list means "the source answered: no news"; a failed or malformed
 * response throws {@link SourceFailureException}.
 */
public interface NewsFeed {

    List<NewsItem> newsSince(String symbol, Instant since);
}
