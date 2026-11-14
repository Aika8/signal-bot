package io.github.signalbot.domain.port;

import io.github.signalbot.domain.model.CashBalance;

import java.util.List;

/** Reads free cash balances per currency. Read-only. */
public interface CashReader {

    List<CashBalance> read();
}
