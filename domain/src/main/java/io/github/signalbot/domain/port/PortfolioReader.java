package io.github.signalbot.domain.port;

import io.github.signalbot.domain.model.Portfolio;

/** Reads the current portfolio. Read-only; never places orders. */
public interface PortfolioReader {

    Portfolio read();
}
