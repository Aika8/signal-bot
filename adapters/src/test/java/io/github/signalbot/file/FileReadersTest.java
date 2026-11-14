package io.github.signalbot.file;

import io.github.signalbot.config.PortfolioProperties;
import io.github.signalbot.config.PortfolioSource;
import io.github.signalbot.domain.model.Ccy;
import io.github.signalbot.domain.model.Portfolio;
import io.github.signalbot.domain.model.Venue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileReadersTest {

    private static final String YAML = """
            portfolio:
              totalValueKzt: 1500000
              asOf: 2026-10-23T10:00:00Z
              fxRates:
                USD: 525.0
              positions:
                - secCode: AAPL
                  isin: US0378331005
                  venue: US
                  quantity: 10
                  available: 10
                  avgEntryPrice: 150.00
                  lastPrice: 165.00
                  priceCurrency: USD
                  marketValueKzt: 866250
                  unrealizedPnl: 150.00
                  unrealizedPnlPct: 0.10
                  hasTakeProfit: false
                  hasStopLoss: false
                  openedAt: 2026-09-01T14:30:00Z
            cash:
              - currency: KZT
                balance: 300000
                balanceKzt: 300000
                reservedByOpenBuyOrders: 0
            """;

    private AccountFileLoader loader(Path file) {
        return new AccountFileLoader(new PortfolioProperties(PortfolioSource.FILE, file.toString()));
    }

    @Test
    void readsPortfolioAndCashFromYaml(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("portfolio.yml");
        Files.writeString(file, YAML);
        AccountFileLoader loader = loader(file);

        Portfolio portfolio = new FilePortfolioReader(loader).read();
        assertEquals(1, portfolio.positions().size());
        assertEquals("AAPL", portfolio.positions().get(0).secCode());
        assertEquals(Venue.US, portfolio.positions().get(0).venue());
        assertEquals(0, portfolio.positions().get(0).lastPrice().compareTo(new BigDecimal("165.00")));
        assertEquals(0, portfolio.totalValueKzt().compareTo(new BigDecimal("1500000")));

        var cash = new FileCashReader(loader).read();
        assertEquals(Ccy.KZT, cash.get(0).currency());
        assertEquals(0, cash.get(0).balance().compareTo(new BigDecimal("300000")));
    }

    @Test
    void missingFileFailsClearly(@TempDir Path dir) {
        AccountFileLoader loader = loader(dir.resolve("absent.yml"));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new FilePortfolioReader(loader).read());
        assertTrue(e.getMessage().contains("not found"));
    }
}
