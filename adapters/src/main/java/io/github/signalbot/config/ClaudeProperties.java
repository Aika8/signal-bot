package io.github.signalbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Claude decision-engine settings. {@code enabled} gates the live engine; when
 * off (the default) the service stays in dry-run and never calls Claude. The
 * API key is read from the environment by the SDK (ANTHROPIC_API_KEY), not here.
 *
 * @param enabled   whether the Claude decision engine is active
 * @param model     model id (default claude-opus-5-5)
 * @param maxTokens response token cap for a verdict
 */
@ConfigurationProperties(prefix = "signalbot.claude")
public record ClaudeProperties(boolean enabled, String model, long maxTokens) {
}
