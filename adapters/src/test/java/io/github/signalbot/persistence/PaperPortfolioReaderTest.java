package io.github.signalbot.persistence;

import io.github.signalbot.config.FxConverter;
import io.github.signalbot.domain.model.Ccy;
import io.github.signalbot.domain.model.PriceSnapshot;
import io.github.signalbot.domain.model.Venue;
import io.github.signalbot.domain.port.MarketDataFeed;
import io.github.signalbot.domain.port.SourceFailureException;
import io.github.signalbot.persistence.PositionStore.PaperPositionRow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaperPortfolioReaderTest {

    private final PositionStore positions = mock(PositionStore.class);
    private final CashStore cash = mock(CashStore.class);
    private final FxConverter fx = mock(FxConverter.class);
    private final MarketDataFeed feed = mock(MarketDataFeed.class);

    @SuppressWarnings("unchecked")
    private PaperPortfolioReader reader(MarketDataFeed f) {
        ObjectProvider<MarketDataFeed> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(f);
        when(positions.findAll()).thenReturn(List.of(new PaperPositionRow("AAPL", null, Venue.US,
                BigDecimal.ONE, new BigDecimal("100"), Ccy.USD, Instant.now())));
        when(cash.findAll()).thenReturn(List.of());
        when(fx.toKzt(any(), any())).thenAnswer(i -> i.getArgument(0));
        when(fx.rateOf(any())).thenReturn(BigDecimal.ONE);
        return new PaperPortfolioReader(positions, cash, fx, p);
    }

    @Test
    void missingLastPriceFailsInsteadOfValuingAtCost() {
        when(feed.snapshot("AAPL")).thenReturn(new PriceSnapshot("AAPL", null, null, Instant.now()));
        assertThrows(SourceFailureException.class, () -> reader(feed).read());
    }

    @Test
    void snapshotErrorFails() {
        when(feed.snapshot("AAPL")).thenThrow(new RuntimeException("circuit open"));
        assertThrows(SourceFailureException.class, () -> reader(feed).read());
    }

    @Test
    void usesLastPriceWhenAvailable() {
        when(feed.snapshot("AAPL")).thenReturn(
                new PriceSnapshot("AAPL", new BigDecimal("110"), new BigDecimal("100"), Instant.now()));
        assertEquals(0, reader(feed).read().positions().get(0).lastPrice().compareTo(new BigDecimal("110")));
    }

    @Test
    void noFeedValuesAtCost() {
        assertEquals(0, reader(null).read().positions().get(0).lastPrice().compareTo(new BigDecimal("100")));
    }
}
