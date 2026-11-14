package io.github.signalbot.trigger;

import io.github.signalbot.domain.model.CashBalance;
import io.github.signalbot.domain.model.DecisionContext;
import io.github.signalbot.domain.model.NewsItem;
import io.github.signalbot.domain.model.Portfolio;
import io.github.signalbot.domain.model.PriceSnapshot;
import io.github.signalbot.domain.model.Verdict;
import io.github.signalbot.domain.port.CashReader;
import io.github.signalbot.domain.port.MarketDataFeed;
import io.github.signalbot.domain.port.NewsFeed;
import io.github.signalbot.domain.port.PortfolioReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Assembles a {@link DecisionContext} for one trigger. Feeds are optional. */
@Component
public class ContextAssembler {

    private static final Logger LOG = LoggerFactory.getLogger(ContextAssembler.class);

    private final PortfolioReader portfolioReader;
    private final CashReader cashReader;
    private final ObjectProvider<MarketDataFeed> marketData;
    private final ObjectProvider<NewsFeed> newsFeed;

    public ContextAssembler(PortfolioReader portfolioReader, CashReader cashReader,
                            ObjectProvider<MarketDataFeed> marketData, ObjectProvider<NewsFeed> newsFeed) {
        this.portfolioReader = portfolioReader;
        this.cashReader = cashReader;
        this.marketData = marketData;
        this.newsFeed = newsFeed;
    }

    public DecisionContext assemble(String symbol, String reason, Verdict previousVerdict) {
        Portfolio portfolio = portfolioReader.read();
        List<CashBalance> cash = cashReader.read();
        PriceSnapshot snapshot = snapshot(symbol);
        List<NewsItem> news = recentNews(symbol);
        return new DecisionContext(symbol, reason, portfolio, cash, news, snapshot, previousVerdict);
    }

    public PriceSnapshot snapshot(String symbol) {
        MarketDataFeed feed = marketData.getIfAvailable();
        if (feed == null) {
            return null;
        }
        try {
            return feed.snapshot(symbol);
        } catch (Exception e) {
            LOG.warn("Snapshot failed for {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    private List<NewsItem> recentNews(String symbol) {
        NewsFeed feed = newsFeed.getIfAvailable();
        if (feed == null) {
            return List.of();
        }
        try {
            return feed.newsSince(symbol, Instant.now().minus(Duration.ofHours(24)));
        } catch (Exception e) {
            LOG.warn("News fetch failed for {}: {}", symbol, e.getMessage());
            return List.of();
        }
    }
}
