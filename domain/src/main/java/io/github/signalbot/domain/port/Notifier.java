package io.github.signalbot.domain.port;

import io.github.signalbot.domain.model.Verdict;

/** Sends a verdict with a short context summary to a human channel. */
public interface Notifier {

    void send(Verdict verdict, String contextSummary);
}
