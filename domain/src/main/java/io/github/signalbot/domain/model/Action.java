package io.github.signalbot.domain.model;

/** The action a verdict recommends. HOLD never results in a trade. */
public enum Action {
    BUY,
    SELL,
    HOLD
}
