package io.github.signalbot.execution;

/** Lifecycle of a confirmation-gated execution. */
public enum ExecutionState {
    PENDING,
    FILLED,
    SKIPPED,
    EXPIRED,
    /** The paper wallet rejected the fill (e.g. no position to sell); nothing was booked. */
    FAILED
}
