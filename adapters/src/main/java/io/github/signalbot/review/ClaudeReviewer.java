package io.github.signalbot.review;

import io.github.signalbot.decision.ClaudeClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** One Claude call that turns a day's trading summary into a short written review. */
@Component
@ConditionalOnProperty(prefix = "signalbot.claude", name = "enabled", havingValue = "true")
public class ClaudeReviewer {

    private static final String SYSTEM = """
            You are reviewing one day of PAPER trading (no real money). Given the summary,
            write a concise plain-text review of 3-6 sentences: what happened, what worked,
            what to watch tomorrow. Do not use JSON or markdown headings.
            """;

    private final ClaudeClient claude;

    public ClaudeReviewer(ClaudeClient claude) {
        this.claude = claude;
    }

    public String review(String daySummary) {
        return claude.complete(SYSTEM, daySummary);
    }
}
