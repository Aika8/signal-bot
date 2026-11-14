package io.github.signalbot.application.trigger;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Thresholds for the trigger rules.
 *
 * @param priceMovePct minimum absolute move from the reference price (0.03 == 3%)
 * @param priceWindow  the window within which the move must occur
 */
public record TriggerSettings(BigDecimal priceMovePct, Duration priceWindow) {
}
