package io.github.signalbot.application.account;

import io.github.signalbot.domain.port.CashReader;
import io.github.signalbot.domain.port.PortfolioReader;

/** Assembles a read-only account snapshot from the portfolio and cash ports. */
public class AccountQueryService {

    private final PortfolioReader portfolioReader;
    private final CashReader cashReader;

    public AccountQueryService(PortfolioReader portfolioReader, CashReader cashReader) {
        this.portfolioReader = portfolioReader;
        this.cashReader = cashReader;
    }

    public AccountSnapshot snapshot() {
        return new AccountSnapshot(portfolioReader.read(), cashReader.read());
    }
}
