package io.github.signalbot.execution;

/** Lifecycle of a confirmation-gated execution. */
public enum ExecutionState {
    PENDING,
    FILLED,
    SKIPPED,
    EXPIRED
}
