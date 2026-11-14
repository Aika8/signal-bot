package io.github.signalbot.file;

import io.github.signalbot.config.PortfolioProperties;
import io.github.signalbot.domain.model.Ccy;
import io.github.signalbot.domain.model.Portfolio;
import io.github.signalbot.domain.model.Position;
import io.github.signalbot.domain.model.Venue;
import io.github.signalbot.domain.port.PortfolioReader;
import io.github.signalbot.file.FileDocument.FilePortfolio;
import io.github.signalbot.file.FileDocument.FilePosition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Reads the portfolio from a hand-edited YAML file. Active when portfolio.source=file. */
@Component
@ConditionalOnProperty(prefix = "signalbot.portfolio", name = "source", havingValue = "file")
public class FilePortfolioReader implements PortfolioReader {

    private final AccountFileLoader loader;

    public FilePortfolioReader(AccountFileLoader loader) {
        this.loader = loader;
    }

    @Override
    public Portfolio read() {
        FilePortfolio p = loader.load().portfolio();
        if (p == null) {
            return new Portfolio(List.of(), BigDecimal.ZERO, Map.of(), Instant.now());
        }
        List<Position> positions = (p.positions() == null ? List.<FilePosition>of() : p.positions()).stream()
                .map(FilePortfolioReader::toPosition)
                .toList();
        Map<Ccy, BigDecimal> fxRates = p.fxRates() == null ? Map.of()
                : p.fxRates().entrySet().stream()
                        .collect(Collectors.toMap(e -> Ccy.valueOf(e.getKey()), Map.Entry::getValue));
        return new Portfolio(positions, p.totalValueKzt(), fxRates, p.asOf() != null ? p.asOf() : Instant.now());
    }

    private static Position toPosition(FilePosition f) {
        return new Position(f.secCode(), f.isin(), Venue.valueOf(f.venue()), f.quantity(), f.available(),
                f.avgEntryPrice(), f.lastPrice(), Ccy.valueOf(f.priceCurrency()), f.marketValueKzt(),
                f.unrealizedPnl(), f.unrealizedPnlPct(), f.hasTakeProfit(), f.hasStopLoss(), f.openedAt());
    }
}
