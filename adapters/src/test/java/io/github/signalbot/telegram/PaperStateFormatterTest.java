package io.github.signalbot.telegram;

import io.github.signalbot.application.account.AccountSnapshot;
import io.github.signalbot.domain.model.CashBalance;
import io.github.signalbot.domain.model.Ccy;
import io.github.signalbot.domain.model.Portfolio;
import io.github.signalbot.domain.model.Position;
import io.github.signalbot.domain.model.Venue;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperStateFormatterTest {

    private final PaperStateFormatter formatter = new PaperStateFormatter();

    @Test
    void rendersPositionsAndCash() {
        Position aapl = new Position("AAPL", "US0378331005", Venue.US,
                new BigDecimal("2"), new BigDecimal("2"), new BigDecimal("100"), new BigDecimal("120"),
                Ccy.USD, new BigDecimal("126000"), new BigDecimal("40"), new BigDecimal("0.20"),
                false, false, Instant.now());
        Portfolio portfolio = new Portfolio(List.of(aapl), new BigDecimal("320769"),
                Map.of(Ccy.USD, new BigDecimal("525")), Instant.now());
        AccountSnapshot snapshot = new AccountSnapshot(portfolio,
                List.of(new CashBalance(Ccy.KZT, new BigDecimal("194769"), new BigDecimal("194769"), BigDecimal.ZERO)));

        String text = formatter.format(snapshot);

        assertTrue(text.contains("AAPL"));
        assertTrue(text.contains("20.00%"));
        assertTrue(text.contains("KZT: 194769"));
        assertTrue(text.contains("Total: 320769.00 KZT"));
    }

    @Test
    void rendersEmptyPortfolio() {
        Portfolio portfolio = new Portfolio(List.of(), new BigDecimal("300000"), Map.of(), Instant.now());
        AccountSnapshot snapshot = new AccountSnapshot(portfolio,
                List.of(new CashBalance(Ccy.KZT, new BigDecimal("300000"), new BigDecimal("300000"), BigDecimal.ZERO)));

        String text = formatter.format(snapshot);

        assertTrue(text.contains("No open positions"));
    }
}
