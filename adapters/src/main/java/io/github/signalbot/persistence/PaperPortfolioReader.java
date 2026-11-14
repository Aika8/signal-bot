package io.github.signalbot.persistence;

import io.github.signalbot.config.FxConverter;
import io.github.signalbot.domain.model.Ccy;
import io.github.signalbot.domain.model.Portfolio;
import io.github.signalbot.domain.model.Position;
import io.github.signalbot.domain.model.PriceSnapshot;
import io.github.signalbot.domain.port.MarketDataFeed;
import io.github.signalbot.domain.port.PortfolioReader;
import io.github.signalbot.persistence.PositionStore.PaperPositionRow;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Builds a {@link Portfolio} from the paper positions and cash. Read-only. */
@Component
@ConditionalOnProperty(prefix = "signalbot.portfolio", name = "source", havingValue = "paper", matchIfMissing = true)
public class PaperPortfolioReader implements PortfolioReader {

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
            return row.avgEntryPrice();
        }
        PriceSnapshot snapshot = feed.snapshot(row.secCode());
        return snapshot != null && snapshot.last() != null ? snapshot.last() : row.avgEntryPrice();
    }
}
