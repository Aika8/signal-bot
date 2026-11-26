package io.github.signalbot.trigger;

import io.github.signalbot.application.trigger.ExitGuard;
import io.github.signalbot.application.trigger.TriggerEvaluator;
import io.github.signalbot.config.WatchlistProperties;
import io.github.signalbot.domain.port.MarketDataFeed;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Subscribes to the live price stream (if enabled). Each tick raises T1 (price
 * move) triggers and is checked against the protective exit guard.
 */
@Component
public class PriceStreamRunner implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(PriceStreamRunner.class);

    private final ObjectProvider<MarketDataFeed> marketDataFeed;
    private final WatchlistProperties watchlist;
    private final TriggerEvaluator evaluator;
    private final SignalDispatcher dispatcher;
    private final ExitGuard exitGuard;
    private final ExitGuardHandler exitHandler;
    private final TrailingStateWriter trailingWriter;
    private AutoCloseable subscription;

    public PriceStreamRunner(ObjectProvider<MarketDataFeed> marketDataFeed, WatchlistProperties watchlist,
                             TriggerEvaluator evaluator, SignalDispatcher dispatcher,
                             ExitGuard exitGuard, ExitGuardHandler exitHandler,
                             TrailingStateWriter trailingWriter) {
        this.marketDataFeed = marketDataFeed;
        this.watchlist = watchlist;
        this.evaluator = evaluator;
        this.dispatcher = dispatcher;
        this.exitGuard = exitGuard;
        this.exitHandler = exitHandler;
        this.trailingWriter = trailingWriter;
    }

    @Override
    public void run(ApplicationArguments args) {
        MarketDataFeed feed = marketDataFeed.getIfAvailable();
        if (feed == null) {
            LOG.info("Market data feed disabled; price-move triggers and tick-based exits inactive.");
            return;
        }
        subscription = feed.subscribe(watchlist.symbols(), tick -> {
            exitGuard.onPrice(tick.symbol(), tick.price()).ifPresent(exitHandler::handle);
            trailingWriter.afterPrice(tick.symbol());
            evaluator.onPriceTick(tick.symbol(), tick.price(), tick.ts()).ifPresent(dispatcher::dispatch);
        });
    }

    @PreDestroy
    public void stop() {
        if (subscription != null) {
            try {
                subscription.close();
            } catch (Exception e) {
                LOG.warn("Failed to close market-data subscription: {}", e.getMessage());
            }
        }
    }
}
