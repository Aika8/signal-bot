package io.github.signalbot.persistence;

import io.github.signalbot.config.PaperWalletProperties;
import io.github.signalbot.domain.model.Ccy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Seeds the paper wallet's KZT cash to the configured amount while it is pristine (no trades yet). */
@Component
@Order(1)
public class PaperWalletSeeder implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(PaperWalletSeeder.class);

    private final CashStore cash;
    private final TradeQueryStore trades;
    private final PaperWalletProperties props;

    public PaperWalletSeeder(CashStore cash, TradeQueryStore trades, PaperWalletProperties props) {
        this.cash = cash;
        this.trades = trades;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (trades.totalCount() == 0) {
            cash.setBalance(Ccy.KZT, props.seedKzt());
            LOG.info("Seeded pristine paper wallet with {} KZT", props.seedKzt());
        }
    }
}
