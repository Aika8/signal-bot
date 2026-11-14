package io.github.signalbot.domain.port;

import io.github.signalbot.domain.model.NewsItem;

import java.time.Instant;
import java.util.List;

/** Returns news for a symbol published since a given timestamp. */
public interface NewsFeed {

    List<NewsItem> newsSince(String symbol, Instant since);
}
