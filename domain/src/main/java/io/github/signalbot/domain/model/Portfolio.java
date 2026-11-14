package io.github.signalbot.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A snapshot of the whole portfolio.
 *
 * @param positions     current holdings
 * @param totalValueKzt total value (positions + cash) in KZT
 * @param fxRates       currency-to-KZT rates used for this snapshot
 * @param asOf          snapshot timestamp
 */
public record Portfolio(
        List<Position> positions,
        BigDecimal totalValueKzt,
        Map<Ccy, BigDecimal> fxRates,
        Instant asOf
) {
    public Portfolio {
        positions = positions == null ? List.of() : List.copyOf(positions);
        fxRates = fxRates == null ? Map.of() : Map.copyOf(fxRates);
    }
}
