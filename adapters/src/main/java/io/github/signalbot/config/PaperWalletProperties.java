package io.github.signalbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * Paper wallet tuning.
 *
 * @param seedKzt          starting cash for a pristine wallet (KZT)
 * @param feeRate          non-US fallback fee as a fraction of notional (0.001 == 0.1%)
 * @param feePerShareUsd   US venue: fee per share (USD)
 * @param feePerOrderUsd   US venue: fixed fee per order (USD)
 * @param taxRate          fraction of realized gains accrued as tax (0.10 == 10%)
 * @param fillDelaySeconds delay used by the execution scheduler before taking the fill quote
 */
@ConfigurationProperties(prefix = "signalbot.paper")
public record PaperWalletProperties(
        BigDecimal seedKzt,
        BigDecimal feeRate,
        BigDecimal feePerShareUsd,
        BigDecimal feePerOrderUsd,
        BigDecimal taxRate,
        int fillDelaySeconds) {
}
