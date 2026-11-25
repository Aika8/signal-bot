package io.github.signalbot.persistence;

import io.github.signalbot.config.FxConverter;
import io.github.signalbot.domain.model.Ccy;
import io.github.signalbot.domain.model.Portfolio;
import io.github.signalbot.domain.model.Position;
import io.github.signalbot.domain.model.PriceSnapshot;
import io.github.signalbot.domain.port.MarketDataFeed;
import io.github.signalbot.domain.port.PortfolioReader;
import io.github.signalbot.domain.port.SourceFailureException;
import io.github.signalbot.persistence.PositionStore.PaperPositionRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds a {@link Portfolio} from the paper positions and cash. Read-only.
 *
 * <p>Pricing: with a market-data feed configured, every position must get a last
 * price; a failed or empty snapshot throws {@link SourceFailureException} instead
 * of silently valuing the position at its entry price (which would show zero P&amp;L).
 * Only when no feed is configured at all (offline/dry-run) are positions valued
 * at cost, and that is logged.
 */
@Component
@ConditionalOnProperty(prefix = "signalbot.portfolio", name = "source", havingValue = "paper", matchIfMissing = true)
public class PaperPortfolioReader implements PortfolioReader {

    private static final Logger LOG = LoggerFactory.getLogger(PaperPortfolioReader.class);

    private final PositionStore positions;
    private final CashStore cash;
    private final FxConverter fx;
    private final ObjectProvider<MarketDataFeed> feedProvider;

    public PaperPortfolioReader(PositionStore positions, CashStore cash, FxConverter fx,
                                ObjectProvider<MarketDataFeed> feedProvider) {
        this.positions = positions;
        this.cash = cash;
        this.fx = fx;
        this.feedProvider = feedProvider;
    }

    @Override
    public Portfolio read() {
        MarketDataFeed feed = feedProvider.getIfAvailable();
        List<Position> result = new ArrayList<>();
        BigDecimal positionsKzt = BigDecimal.ZERO;

        for (PaperPositionRow row : positions.findAll()) {
            BigDecimal last = lastPrice(feed, row);
            BigDecimal marketValueKzt = fx.toKzt(last.multiply(row.quantity()), row.priceCurrency())
                    .setScale(4, RoundingMode.HALF_UP);
            BigDecimal pnl = last.subtract(row.avgEntryPrice()).multiply(row.quantity());
            BigDecimal pnlPct = row.avgEntryPrice().signum() == 0 ? BigDecimal.ZERO
                    : last.subtract(row.avgEntryPrice()).divide(row.avgEntryPrice(), 6, RoundingMode.HALF_UP);
            positionsKzt = positionsKzt.add(marketValueKzt);
            result.add(new Position(row.secCode(), row.isin(), row.venue(), row.quantity(), row.quantity(),
                    row.avgEntryPrice(), last, row.priceCurrency(), marketValueKzt, pnl, pnlPct,
                    false, false, row.openedAt()));
        }

        BigDecimal cashKzt = cash.findAll().stream()
                .map(c -> fx.toKzt(c.balance(), c.currency()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalKzt = positionsKzt.add(cashKzt).setScale(4, RoundingMode.HALF_UP);

        Map<Ccy, BigDecimal> fxRates = Map.of(
                Ccy.KZT, BigDecimal.ONE, Ccy.USD, fx.rateOf(Ccy.USD), Ccy.EUR, fx.rateOf(Ccy.EUR));
        return new Portfolio(result, totalKzt, fxRates, Instant.now());
    }

    private BigDecimal lastPrice(MarketDataFeed feed, PaperPositionRow row) {
        if (feed == null) {
            LOG.debug("No market-data feed; valuing {} at cost {}", row.secCode(), row.avgEntryPrice());
            return row.avgEntryPrice();
        }
        PriceSnapshot snapshot;
        try {
            snapshot = feed.snapshot(row.secCode());
        } catch (RuntimeException e) {
            throw new SourceFailureException("market-data", "snapshot failed for " + row.secCode()
                    + ": " + e.getMessage(), e);
        }
        if (snapshot == null || snapshot.last() == null) {
            throw new SourceFailureException("market-data", "no last price for " + row.secCode());
        }
        return snapshot.last();
    }
}
