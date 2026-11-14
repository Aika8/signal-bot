package io.github.signalbot.config;

import io.github.signalbot.application.account.AccountQueryService;
import io.github.signalbot.domain.port.CashReader;
import io.github.signalbot.domain.port.PortfolioReader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires framework-free application services as Spring beans. */
@Configuration
public class ApplicationBeans {

    @Bean
    public AccountQueryService accountQueryService(PortfolioReader portfolioReader, CashReader cashReader) {
        return new AccountQueryService(portfolioReader, cashReader);
    }
}
