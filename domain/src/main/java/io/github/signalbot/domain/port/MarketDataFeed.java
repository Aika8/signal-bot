package io.github.signalbot.domain.port;

import io.github.signalbot.domain.model.PriceSnapshot;
import io.github.signalbot.domain.model.PriceTick;

import java.util.Collection;
import java.util.function.Consumer;

/** Streams live prices and answers point-in-time snapshot queries. */
public interface MarketDataFeed {

    /**
     * Subscribes to live ticks for the given symbols.
     *
     * @return a handle; closing it stops the subscription
     */
    AutoCloseable subscribe(Collection<String> symbols, Consumer<PriceTick> onTick);

    /** Returns the latest snapshot (last price and prior close) for a symbol. */
    PriceSnapshot snapshot(String symbol);
}
