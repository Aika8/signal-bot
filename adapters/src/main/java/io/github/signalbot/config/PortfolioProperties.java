package io.github.signalbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Selects the portfolio/cash source.
 *
 * @param source paper (the paper wallet DB), file (a hand-edited YAML), or private (the private broker module)
 * @param file   path to the YAML file when {@code source=file}
 */
@ConfigurationProperties(prefix = "signalbot.portfolio")
public record PortfolioProperties(PortfolioSource source, String file) {
}
