package io.github.signalbot.persistence;

import io.github.signalbot.config.FxConverter;
import io.github.signalbot.domain.model.CashBalance;
import io.github.signalbot.domain.port.CashReader;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.RoundingMode;
import java.util.List;

/** Reads paper cash balances per currency. Read-only. */
@Component
@ConditionalOnProperty(prefix = "signalbot.portfolio", name = "source", havingValue = "paper", matchIfMissing = true)
public class PaperCashReader implements CashReader {

    private final CashStore cash;
    private final FxConverter fx;

    public PaperCashReader(CashStore cash, FxConverter fx) {
        this.cash = cash;
        this.fx = fx;
    }

    @Override
    public List<CashBalance> read() {
        return cash.findAll().stream()
                .map(c -> new CashBalance(
                        c.currency(),
                        c.balance(),
                        fx.toKzt(c.balance(), c.currency()).setScale(4, RoundingMode.HALF_UP),
                        c.reserved()))
                .toList();
    }
}
