package io.github.signalbot.application.risk;

/** Why a verdict was rejected by the hard risk limits. */
public enum RejectionReason {
    DAILY_LOSS_STOP,
    MAX_TRADES_PER_DAY,
    MIN_HOLDING_PERIOD,
    MAX_OPEN_POSITIONS,
    SYMBOL_CONCENTRATION
}
