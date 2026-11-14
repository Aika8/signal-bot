package io.github.signalbot.domain.model;

import java.math.BigDecimal;

/**
 * How a fill's fee was computed, in KZT.
 *
 * @param perShareKzt   US venue per-share component (0 for the percentage model)
 * @param perOrderKzt   US venue per-order component (0 for the percentage model)
 * @param percentageKzt non-US percentage-of-notional component (0 for the US model)
 * @param totalKzt      total fee charged
 * @param model         "us-per-share" or "percentage"
 */
public record FeeBreakdown(
        BigDecimal perShareKzt,
        BigDecimal perOrderKzt,
        BigDecimal percentageKzt,
        BigDecimal totalKzt,
        String model) {
}
