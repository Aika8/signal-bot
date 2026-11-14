package io.github.signalbot.execution;

import java.math.BigDecimal;

/**
 * What actually happened to an execution after a fill/skip request. Callers
 * (Telegram, the sweeper) report this instead of assuming success.
 *
 * @param state the execution's state after the request, or null if not found
 */
public record ExecutionResult(long executionId, Outcome outcome, ExecutionState state,
                              BigDecimal fillPrice, String detail) {

    public enum Outcome {
        /** This request moved the execution PENDING -> FILLED and booked one paper trade. */
        FILLED,
        /** This request moved the execution PENDING -> SKIPPED. */
        SKIPPED,
        /** The execution was already resolved (by another reply, the sweeper or a timeout); nothing changed. */
        NOT_PENDING,
        NOT_FOUND,
        /** "ok" fill but no last price is available; the execution is still PENDING. */
        NO_PRICE,
        /** The paper wallet rejected the fill; the execution is now FAILED and the wallet is unchanged. */
        FAILED
    }

    /** Operator-facing text for Telegram. */
    public String message() {
        return switch (outcome) {
            case FILLED -> "Filled at " + fillPrice + ".";
            case SKIPPED -> "Skipped.";
            case NOT_PENDING -> "Execution " + executionId + " is already " + state + "; nothing changed.";
            case NOT_FOUND -> "Execution " + executionId + " not found.";
            case NO_PRICE -> "No last price available; not filled. Reply with a price.";
            case FAILED -> "Fill failed: " + detail + ". Execution " + executionId
                    + " marked FAILED; paper wallet unchanged.";
        };
    }
}
