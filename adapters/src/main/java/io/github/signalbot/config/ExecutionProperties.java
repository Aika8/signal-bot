package io.github.signalbot.config;

import io.github.signalbot.execution.ExecutionMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Execution/confirmation settings.
 *
 * @param mode          MANUAL (operator confirms) or AUTO (auto-fill after {@code autoFillDelay})
 * @param timeout       a PENDING execution older than this becomes EXPIRED
 * @param autoFillDelay in AUTO mode, how long before a PENDING execution auto-fills at last price
 * @param sweepMs       how often the sweeper runs (ms)
 */
@ConfigurationProperties(prefix = "signalbot.execution")
public record ExecutionProperties(ExecutionMode mode, Duration timeout, Duration autoFillDelay, long sweepMs) {
}
