package io.github.signalbot.domain.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The simulated result of applying a verdict to the paper wallet.
 *
 * @param symbol        instrument symbol
 * @param action        BUY or SELL that was filled
 * @param quantity      filled units
 * @param fillPrice     simulated fill price
 * @param feeBreakdown  how the fee was computed (KZT)
 * @param realizedGain  realized gain on a SELL in KZT (zero otherwise)
 * @param tax           tax accrued on the realized gain (KZT)
 * @param filledAt      when the simulated fill occurred
 */
public record PaperFill(
        String symbol,
        Action action,
        BigDecimal quantity,
        BigDecimal fillPrice,
        FeeBreakdown feeBreakdown,
        BigDecimal realizedGain,
        BigDecimal tax,
        Instant filledAt
) {
}
