package io.github.signalbot.trigger;

import io.github.signalbot.application.trigger.TriggerEvaluator;
import io.github.signalbot.config.WatchlistProperties;
import io.github.signalbot.domain.model.NewsItem;
import io.github.signalbot.domain.port.NewsFeed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** Polls company news for the watchlist and raises T2 (news) triggers. */
@Component
public class NewsPoller {

    private static final Logger LOG = LoggerFactory.getLogger(NewsPoller.class);

    private final ObjectProvider<NewsFeed> newsFeed;
    private final WatchlistProperties watchlist;
    private final TriggerEvaluator evaluator;
    private final SignalDispatcher dispatcher;
    private volatile Instant since = Instant.now();

    public NewsPoller(ObjectProvider<NewsFeed> newsFeed, WatchlistProperties watchlist,
                      TriggerEvaluator evaluator, SignalDispatcher dispatcher) {
        this.newsFeed = newsFeed;
        this.watchlist = watchlist;
        this.evaluator = evaluator;
        this.dispatcher = dispatcher;
    }

    @Scheduled(cron = "${signalbot.trigger.news-poll-cron}")
    public void poll() {
        NewsFeed feed = newsFeed.getIfAvailable();
        if (feed == null) {
            LOG.debug("News feed disabled; skipping poll.");
            return;
        }
        Instant now = Instant.now();
        for (String symbol : watchlist.symbols()) {
            try {
                for (NewsItem item : feed.newsSince(symbol, since)) {
                    evaluator.onNews(symbol, item.headline(), now).ifPresent(dispatcher::dispatch);
                }
            } catch (Exception e) {
                LOG.warn("News poll failed for {}: {}", symbol, e.getMessage());
            }
        }
        since = now;
    }
}
