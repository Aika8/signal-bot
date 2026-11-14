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

    /** Applies the verdict at an explicit fill price (used by confirmed manual fills). */
    PaperFill applyAt(Verdict verdict, java.math.BigDecimal fillPrice);
}
