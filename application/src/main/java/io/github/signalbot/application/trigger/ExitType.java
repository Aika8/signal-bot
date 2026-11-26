package io.github.signalbot.application.trigger;

/** A protective exit that bypasses Claude and the normal risk gates. */
public enum ExitType {
    EXIT_STOP,
    EXIT_TP,
    /** Trailing take-profit (D-004): price pulled back more than the offset from the best price. */
    EXIT_TRAIL
}
