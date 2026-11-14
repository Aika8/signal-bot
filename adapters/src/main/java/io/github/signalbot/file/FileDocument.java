package io.github.signalbot.file;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * YAML shape for the hand-edited portfolio/cash file. Mirrors the port records
 * exactly (venue/currency are strings here, mapped to enums by the readers).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FileDocument(FilePortfolio portfolio, List<FileCash> cash) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FilePortfolio(
            BigDecimal totalValueKzt,
            Instant asOf,
            Map<String, BigDecimal> fxRates,
            List<FilePosition> positions) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FilePosition(
            String secCode, String isin, String venue, BigDecimal quantity, BigDecimal available,
            BigDecimal avgEntryPrice, BigDecimal lastPrice, String priceCurrency, BigDecimal marketValueKzt,
            BigDecimal unrealizedPnl, BigDecimal unrealizedPnlPct, boolean hasTakeProfit, boolean hasStopLoss,
            Instant openedAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FileCash(String currency, BigDecimal balance, BigDecimal balanceKzt,
                           BigDecimal reservedByOpenBuyOrders) {
    }
}
