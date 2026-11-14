package io.github.signalbot.screen;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.List;

/** Inputs for the one-off `screen` CLI. */
@ConfigurationProperties(prefix = "screen")
public record ScreenProperties(List<String> candidates, BigDecimal maxPrice) {

    public ScreenProperties {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        maxPrice = maxPrice == null ? new BigDecimal("250") : maxPrice;
    }
}
