package io.github.signalbot.config;

import io.github.signalbot.domain.port.CashReader;
import io.github.signalbot.domain.port.PortfolioReader;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Startup guard for {@code portfolio.source=private}: if the private broker
 * module is not on the classpath (no PortfolioReader/CashReader bean), fail fast
 * with a clear message instead of a cryptic wiring error.
 */
@Configuration
public class PortfolioSourceConfig {

    private static final String MESSAGE =
            "portfolio.source=private but the private broker module is not on the classpath. "
                    + "Add the adapter-broker-private module (PortfolioReader/CashReader implementations) "
                    + "or set signalbot.portfolio.source to paper or file.";

    @Bean
    @ConditionalOnProperty(prefix = "signalbot.portfolio", name = "source", havingValue = "private")
    @ConditionalOnMissingBean(PortfolioReader.class)
    public PortfolioReader privatePortfolioReaderGuard() {
        throw new IllegalStateException(MESSAGE);
    }

    @Bean
    @ConditionalOnProperty(prefix = "signalbot.portfolio", name = "source", havingValue = "private")
    @ConditionalOnMissingBean(CashReader.class)
    public CashReader privateCashReaderGuard() {
        throw new IllegalStateException(MESSAGE);
    }
}
