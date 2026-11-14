package io.github.signalbot.domain.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A point-in-time quote used by triggers and the paper wallet.
 *
 * @param symbol     instrument symbol
 * @param last       last traded price
 * @param priorClose previous session close
 * @param ts         when the snapshot was taken
 */
public record PriceSnapshot(
        String symbol,
        BigDecimal last,
        BigDecimal priorClose,
        Instant ts
) {
}
