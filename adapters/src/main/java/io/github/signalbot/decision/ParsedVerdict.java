package io.github.signalbot.decision;

import io.github.signalbot.domain.model.Verdict;

/** A parsed verdict plus the model's "what changed since last verdict" note. */
public record ParsedVerdict(Verdict verdict, String whatChanged) {
}
