package io.github.signalbot.application.trigger;

import java.math.BigDecimal;

/**
 * A fired protective exit.
 *
 * @param symbol       the position's symbol
 * @param type         EXIT_STOP or EXIT_TP
 * @param triggerPrice the price that breached the level
 * @param level        the configured stop / take-profit level that was breached
 */
public record ExitSignal(String symbol, ExitType type, BigDecimal triggerPrice, BigDecimal level) {
}
