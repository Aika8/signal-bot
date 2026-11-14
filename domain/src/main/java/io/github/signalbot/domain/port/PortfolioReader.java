package io.github.signalbot.domain.port;

import io.github.signalbot.domain.model.Portfolio;

/** Reads the current portfolio. Read-only; never places orders. Throws {@link SourceFailureException} when the source is missing or malformed (never an empty/zero portfolio). */
public interface PortfolioReader {

    Portfolio read();
}
