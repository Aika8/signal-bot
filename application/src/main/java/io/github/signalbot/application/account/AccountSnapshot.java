package io.github.signalbot.application.account;

import io.github.signalbot.domain.model.CashBalance;
import io.github.signalbot.domain.model.Portfolio;

import java.util.List;

/** A read-only view of the paper account: positions + cash. */
public record AccountSnapshot(Portfolio portfolio, List<CashBalance> cash) {

    public AccountSnapshot {
        cash = cash == null ? List.of() : List.copyOf(cash);
    }
}
