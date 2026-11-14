package io.github.signalbot.trigger;

import io.github.signalbot.domain.model.DecisionContext;
import io.github.signalbot.domain.model.Portfolio;
import io.github.signalbot.domain.model.PriceSnapshot;
import io.github.signalbot.domain.port.CashReader;
import io.github.signalbot.domain.port.MarketDataFeed;
import io.github.signalbot.domain.port.NewsFeed;
import io.github.signalbot.domain.port.PortfolioReader;
import io.github.signalbot.domain.port.SourceFailureException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContextAssemblerTest {

    private final PortfolioReader portfolio = mock(PortfolioReader.class);
    private final CashReader cash = mock(CashReader.class);
    private final MarketDataFeed market = mock(MarketDataFeed.class);
    private final NewsFeed news = mock(NewsFeed.class);

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }

    private ContextAssembler assembler(MarketDataFeed m, NewsFeed n) {
        when(portfolio.read()).thenReturn(new Portfolio(List.of(), BigDecimal.TEN, Map.of(), Instant.now()));
        when(cash.read()).thenReturn(List.of());
        return new ContextAssembler(portfolio, cash, provider(m), provider(n));
    }

    @Test
    void failingNewsFeedFailsTheContext() {
        when(market.snapshot("AAPL")).thenReturn(
                new PriceSnapshot("AAPL", BigDecimal.ONE, BigDecimal.ONE, Instant.now()));
        when(news.newsSince(any(), any())).thenThrow(new RuntimeException("timeout"));
        var e = assertThrows(SourceFailureException.class,
                () -> assembler(market, news).assemble("AAPL", "t", null));
        assertEquals("news", e.source());
    }

    @Test
    void failingSnapshotFailsTheContext() {
        when(market.snapshot("AAPL")).thenThrow(new RuntimeException("circuit open"));
        var e = assertThrows(SourceFailureException.class,
                () -> assembler(market, news).assemble("AAPL", "t", null));
        assertEquals("market-data", e.source());
    }

    @Test
    void disabledFeedsAreMarkedNotAvailable() {
        DecisionContext ctx = assembler(null, null).assemble("AAPL", "t", null);
        assertNull(ctx.snapshot());
        assertFalse(ctx.newsFeedEnabled());
    }
}
