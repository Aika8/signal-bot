package io.github.signalbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Finnhub token and base URL. {@code enabled} gates the live adapters. */
@ConfigurationProperties(prefix = "signalbot.finnhub")
public record FinnhubProperties(boolean enabled, String token, String baseUrl) {
}
