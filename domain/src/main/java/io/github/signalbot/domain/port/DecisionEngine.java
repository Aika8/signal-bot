package io.github.signalbot.domain.port;

import io.github.signalbot.domain.model.DecisionContext;
import io.github.signalbot.domain.model.Verdict;

/**
 * Produces a verdict from an assembled context. Default answer is HOLD, but only
 * when the engine actually answered HOLD: if it cannot answer, it throws
 * {@link DecisionFailedException} instead of returning a fallback verdict.
 */
public interface DecisionEngine {

    Verdict decide(DecisionContext context);
}
