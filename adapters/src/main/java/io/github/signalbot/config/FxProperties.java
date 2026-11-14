package io.github.signalbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/** KZT-per-unit rates for foreign currencies (config for now). */
@ConfigurationProperties(prefix = "signalbot.fx")
public record FxProperties(BigDecimal usdKzt, BigDecimal eurKzt) {
}
