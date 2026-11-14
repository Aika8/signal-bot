package io.github.signalbot.file;

import io.github.signalbot.domain.model.CashBalance;
import io.github.signalbot.domain.model.Ccy;
import io.github.signalbot.domain.port.CashReader;
import io.github.signalbot.domain.port.SourceFailureException;
import io.github.signalbot.file.FileDocument.FileCash;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/** Reads cash balances from a hand-edited YAML file. Active when portfolio.source=file. */
@Component
@ConditionalOnProperty(prefix = "signalbot.portfolio", name = "source", havingValue = "file")
public class FileCashReader implements CashReader {

    private final AccountFileLoader loader;

    public FileCashReader(AccountFileLoader loader) {
        this.loader = loader;
    }

    @Override
    public List<CashBalance> read() {
        List<FileCash> cash = loader.load().cash();
        if (cash == null) {
            // "cash: []" means no cash accounts; an absent section is a broken file.
            throw new SourceFailureException(AccountFileLoader.SOURCE, "'cash' section is missing");
        }
        return cash.stream()
                .map(c -> new CashBalance(Ccy.valueOf(c.currency()), c.balance(), c.balanceKzt(),
                        c.reservedByOpenBuyOrders()))
                .toList();
    }
}
