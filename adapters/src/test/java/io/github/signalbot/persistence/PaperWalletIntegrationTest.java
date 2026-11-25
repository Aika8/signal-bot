package io.github.signalbot.persistence;

import io.github.signalbot.domain.model.Action;
import io.github.signalbot.domain.model.Ccy;
import io.github.signalbot.domain.model.CashBalance;
import io.github.signalbot.domain.model.PriceSnapshot;
import io.github.signalbot.domain.model.PriceTick;
import io.github.signalbot.domain.model.Verdict;
import io.github.signalbot.domain.port.CashReader;
import io.github.signalbot.domain.port.MarketDataFeed;
import io.github.signalbot.domain.port.PaperWallet;
import io.github.signalbot.domain.port.PortfolioReader;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@Import(PaperWalletIntegrationTest.FakeFeedConfig.class)
class PaperWalletIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    PaperWallet paperWallet;
    @Autowired
    PortfolioReader portfolioReader;
    @Autowired
    CashReader cashReader;
    @Autowired
    FakeMarketDataFeed feed;
    @Autowired
    JdbcClient db;

    @Test
    void buyThenSellSettlesCashTaxAndPnlInKzt() {
        // Seed is 1,000,000 KZT (configurable PAPER_SEED_KZT, default 1,000,000).
        assertEquals(0, kztCash().compareTo(new BigDecimal("1000000")));

        // BUY 2 @ 100 USD; fx 525 => notional 105000 KZT.
        // US fee = 0.005*2*525 + 1.00*525 = 530.25 KZT. Cash = 1,000,000 - 105000 - 530.25.
        feed.set("AAPL", new BigDecimal("100"));
        paperWallet.apply(verdict(Action.BUY, "AAPL", 2));

        assertEquals(0, kztCash().compareTo(new BigDecimal("894469.7500")));
        assertEquals(1, portfolioReader.read().positions().size());
        assertEquals(0, countTrades().compareTo(BigDecimal.ONE));

        // SELL 2 @ 120 USD; realized (120-100)*2*525 = 21000 KZT; tax 2100; proceeds 126000 - 530.25 fee.
        feed.set("AAPL", new BigDecimal("120"));
        paperWallet.apply(verdict(Action.SELL, "AAPL", 2));

        assertEquals(0, kztCash().compareTo(new BigDecimal("1019939.5000")));
        assertTrue(portfolioReader.read().positions().isEmpty());
        assertEquals(0, sum("tax_ledger", "tax_amount").compareTo(new BigDecimal("2100.0000")));
        assertEquals(0, sum("daily_pnl", "realized_pnl").compareTo(new BigDecimal("21000.0000")));
    }

    private BigDecimal kztCash() {
        return cashReader.read().stream()
                .filter(c -> c.currency() == Ccy.KZT)
                .map(CashBalance::balance)
                .findFirst()
                .orElse(BigDecimal.ZERO);
    }

    private BigDecimal countTrades() {
        return db.sql("SELECT COUNT(*) FROM paper_trade").query(BigDecimal.class).single();
    }

    private BigDecimal sum(String table, String column) {
        return db.sql("SELECT COALESCE(SUM(" + column + "), 0) FROM " + table)
                .query(BigDecimal.class).single();
    }

    private static Verdict verdict(Action action, String symbol, int qty) {
        return new Verdict(symbol, action, BigDecimal.valueOf(qty), null, null, null,
                0.9, "integration test", Instant.now().plusSeconds(300));
    }

    @TestConfiguration
    static class FakeFeedConfig {
        @Bean
        FakeMarketDataFeed fakeMarketDataFeed() {
            return new FakeMarketDataFeed();
        }
    }

    /** In-memory feed whose last price is set per test step. */
    static class FakeMarketDataFeed implements MarketDataFeed {
        private final Map<String, BigDecimal> last = new ConcurrentHashMap<>();

        void set(String symbol, BigDecimal price) {
            last.put(symbol, price);
        }

        @Override
        public AutoCloseable subscribe(Collection<String> symbols, Consumer<PriceTick> onTick) {
            return () -> { };
        }

        @Override
        public PriceSnapshot snapshot(String symbol) {
            BigDecimal price = last.getOrDefault(symbol, BigDecimal.ZERO);
            return new PriceSnapshot(symbol, price, price, Instant.now());
        }
    }
}
