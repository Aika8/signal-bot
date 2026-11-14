package io.github.signalbot.domain.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A single holding in the portfolio.
 *
 * @param secCode          instrument code / ticker
 * @param isin             ISIN, may be null if unknown
 * @param venue            trading venue
 * @param quantity         total units held
 * @param available        units free to sell (not locked by open orders)
 * @param avgEntryPrice    average entry price, in {@code priceCurrency}
 * @param lastPrice        last known price, in {@code priceCurrency}
 * @param priceCurrency    currency of {@code avgEntryPrice} and {@code lastPrice}
 * @param marketValueKzt   current market value converted to KZT
 * @param unrealizedPnl    unrealized P&amp;L in {@code priceCurrency}
 * @param unrealizedPnlPct unrealized P&amp;L as a fraction (0.05 == +5%)
 * @param hasTakeProfit    whether a take-profit order is active for this symbol
 * @param hasStopLoss      whether a stop-loss order is active for this symbol
 * @param openedAt         when the position was first opened
 */
public record Position(
        String secCode,
        String isin,
        Venue venue,
        BigDecimal quantity,
        BigDecimal available,
        BigDecimal avgEntryPrice,
        BigDecimal lastPrice,
        Ccy priceCurrency,
        BigDecimal marketValueKzt,
        BigDecimal unrealizedPnl,
        BigDecimal unrealizedPnlPct,
        boolean hasTakeProfit,
        boolean hasStopLoss,
        Instant openedAt
) {
}
