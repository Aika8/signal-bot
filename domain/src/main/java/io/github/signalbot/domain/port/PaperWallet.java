package io.github.signalbot.domain.port;

import io.github.signalbot.domain.model.PaperFill;
import io.github.signalbot.domain.model.Verdict;

/**
 * Applies a verdict to a simulated wallet. This is the ONLY execution path in
 * the service: no real orders are ever placed, so there is no "place order" port.
 */
public interface PaperWallet {

    /** Applies the verdict at the current market price (used by AUTO fills). */
    PaperFill apply(Verdict verdict);

    /** Applies the verdict at an explicit fill price, not tied to an execution. */
    default PaperFill applyAt(Verdict verdict, java.math.BigDecimal fillPrice) {
        return applyAt(verdict, fillPrice, null);
    }

    /**
     * Applies the verdict at an explicit fill price for a confirmed execution.
     * The resulting trade carries {@code executionId}; a second apply for the same
     * execution must fail rather than book the trade twice.
     *
     * @param executionId the confirmed execution, or null when not execution-driven
     */
    PaperFill applyAt(Verdict verdict, java.math.BigDecimal fillPrice, Long executionId);
}
