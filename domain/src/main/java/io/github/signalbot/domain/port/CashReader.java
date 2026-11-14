package io.github.signalbot.domain.port;

import io.github.signalbot.domain.model.CashBalance;

import java.util.List;

/** Reads free cash balances per currency. Read-only. Empty list = no cash accounts; a missing or malformed source throws {@link SourceFailureException}. */
public interface CashReader {

    List<CashBalance> read();
}
