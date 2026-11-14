package io.github.signalbot.screen;

import java.math.BigDecimal;

/** One daily OHLCV bar. */
public record Bar(BigDecimal high, BigDecimal low, BigDecimal close, long volume) {
}
