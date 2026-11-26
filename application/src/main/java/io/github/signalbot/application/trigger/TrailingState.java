package io.github.signalbot.application.trigger;

import java.math.BigDecimal;

/**
 * The mutable part of a trailing take-profit, as the adapter persists it (D-004: a restart must not reset trailing).
 *
 * @param best      the best last-trade price since activation; {@code null} before activation (not tracked)
 * @param activated whether the price has reached the activation level
 */
public record TrailingState(BigDecimal best, boolean activated) {

    public TrailingState {
        if (activated && best == null) {
            throw new IllegalArgumentException("an activated trailing exit needs a best price");
        }
        if (!activated && best != null) {
            throw new IllegalArgumentException("the best price is not tracked before activation");
        }
    }
}
