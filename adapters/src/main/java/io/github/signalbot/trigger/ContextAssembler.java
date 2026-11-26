package io.github.signalbot.trigger;

import io.github.signalbot.application.risk.Atr;
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
import io.github.signalbot.domain.port.PriceHistory;
import io.github.signalbot.domain.port.SourceFailureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

/**
 * Assembles a {@link DecisionContext} for one trigger. Feeds are optional, but a
 * configured feed that fails makes {@link #assemble} throw: a verdict is never
 * built on silently missing data. A disabled feed is recorded as "not available".
 * The ATR is the exception: it is context for Claude, not a gate (the gate is the live BUY path), so a missing or
 * failing price history only makes it "not available" (logged).
 */
@Component
public class ContextAssembler {

    private static final Logger LOG = LoggerFactory.getLogger(ContextAssembler.class);

    private final PortfolioReader portfolioReader;
    private final CashReader cashReader;
    private final ObjectProvider<MarketDataFeed> marketData;
    private final ObjectProvider<NewsFeed> newsFeed;
    private final ObjectProvider<PriceHistory> priceHistory;

    /** Without price history: the ATR is always "not available". */
    public ContextAssembler(PortfolioReader portfolioReader, CashReader cashReader,
                            ObjectProvider<MarketDataFeed> marketData, ObjectProvider<NewsFeed> newsFeed) {
        this(portfolioReader, cashReader, marketData, newsFeed, null);
    }

    /** @param priceHistory daily bars for the ATR line of the prompt; an absent bean means "not available" */
    @Autowired
    public ContextAssembler(PortfolioReader portfolioReader, CashReader cashReader,
                            ObjectProvider<MarketDataFeed> marketData, ObjectProvider<NewsFeed> newsFeed,
                            ObjectProvider<PriceHistory> priceHistory) {
        this.portfolioReader = portfolioReader;
        this.cashReader = cashReader;
        this.marketData = marketData;
        this.newsFeed = newsFeed;
        this.priceHistory = priceHistory;
    }

    /** @throws SourceFailureException if any configured source fails */
    public DecisionContext assemble(String symbol, String reason, Verdict previousVerdict) {
        Portfolio portfolio = read("portfolio", portfolioReader::read);
        List<CashBalance> cash = read("cash", cashReader::read);
        PriceSnapshot snapshot = strictSnapshot(symbol);
        NewsFeed feed = newsFeed.getIfAvailable();
        List<NewsItem> news = feed == null ? List.of()
                : read("news", () -> feed.newsSince(symbol, Instant.now().minus(Duration.ofHours(24))));
        BigDecimal atr = atr(symbol);
        return new DecisionContext(symbol, reason, portfolio, cash, news, snapshot, previousVerdict, feed != null,
                atr);
    }

    /**
     * ATR(14) by the same rule and bar count as the live BUY path, or null when not available. Never throws: a
     * failing history is logged and leaves the ATR out (context, not a gate).
     */
    private BigDecimal atr(String symbol) {
        PriceHistory history = priceHistory == null ? null : priceHistory.getIfAvailable();
        if (history == null) {
            return null;
        }
        try {
            BigDecimal atr = Atr.wilder(history.dailyBars(symbol, LiveDecisionHandler.ATR_BARS),
                    LiveDecisionHandler.ATR_PERIOD).orElse(null);
            if (atr == null) {
                LOG.info("ATR not available for {}: not enough daily bars", symbol);
                return null;
            }
            if (atr.signum() <= 0) {
                LOG.info("ATR not available for {}: ATR is {}", symbol, atr);
                return null;
            }
            return atr;
        } catch (RuntimeException e) {
            LOG.warn("ATR not available for {}: price history failed: {}", symbol, e.getMessage());
            return null;
        }
    }

    /**
     * Best-effort snapshot for execution and guard polling: null when the feed is
     * disabled or fails (callers handle null explicitly). Not used for decisions.
     */
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

    private PriceSnapshot strictSnapshot(String symbol) {
        MarketDataFeed feed = marketData.getIfAvailable();
        if (feed == null) {
            return null;
        }
        PriceSnapshot s = read("market-data", () -> feed.snapshot(symbol));
        if (s == null || s.last() == null) {
            throw new SourceFailureException("market-data", "no last price for " + symbol);
        }
        return s;
    }

    private static <T> T read(String source, Supplier<T> call) {
        try {
            return call.get();
        } catch (SourceFailureException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new SourceFailureException(source, e.getMessage(), e);
        }
    }
}
