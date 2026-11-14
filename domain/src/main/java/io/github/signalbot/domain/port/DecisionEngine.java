package io.github.signalbot.domain.port;

import io.github.signalbot.domain.model.DecisionContext;
import io.github.signalbot.domain.model.Verdict;

/** Produces a verdict from an assembled context. Default answer is HOLD. */
public interface DecisionEngine {

    Verdict decide(DecisionContext context);
}
