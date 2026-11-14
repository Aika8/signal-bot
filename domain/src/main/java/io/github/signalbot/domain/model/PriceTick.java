package io.github.signalbot.domain.model;

import java.math.BigDecimal;
import java.time.Instant;

/** A single streamed price update. */
public record PriceTick(
        String symbol,
        BigDecimal price,
        Instant ts
) {
}
