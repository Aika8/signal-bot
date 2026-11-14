package io.github.signalbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Alpaca credentials and endpoints. {@code enabled} gates the live adapters. */
@ConfigurationProperties(prefix = "signalbot.alpaca")
public record AlpacaProperties(
        boolean enabled,
        String keyId,
        String apiSecret,
        String dataUrl,
        String streamUrl,
        String tradingUrl) {
}
