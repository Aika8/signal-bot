package io.github.signalbot.execution;

import io.github.signalbot.application.risk.StopSource;

import java.math.BigDecimal;

/**
 * The sizing facts of a BUY execution (D-002, D-003), kept on the {@code executions} row (V7 columns) so a fill
 * can later be read in R. Exits and SELLs have none: their execution carries {@code null} instead of this record.
 * Any component may be {@code null} when the fact was not known at submit time (e.g. Claude gave no stop).
 */
public record SizingInfo(StopSource stopSource, BigDecimal claudeStop, BigDecimal atr,
                         BigDecimal riskPerShare, BigDecimal riskKzt, BigDecimal claudeQuantity) {
}
