package io.github.signalbot.decision;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.signalbot.domain.model.DecisionContext;
import io.github.signalbot.domain.model.Verdict;
import io.github.signalbot.domain.port.DecisionEngine;
import io.github.signalbot.domain.port.DecisionFailedException;
import io.github.signalbot.persistence.DecisionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * Calls Claude once per trigger, parses a strict-JSON verdict and stores the
 * request/response in the decisions table. If the call fails or the response
 * is unusable, the attempt is stored with action {@code ERROR} and a
 * {@link DecisionFailedException} is thrown: there is no fallback HOLD.
 * Loaded only when {@code signalbot.claude.enabled=true}.
 */
@Component
@ConditionalOnProperty(prefix = "signalbot.claude", name = "enabled", havingValue = "true")
public class ClaudeDecisionEngine implements DecisionEngine {

    static final String ERROR_ACTION = "ERROR";

    private static final Logger LOG = LoggerFactory.getLogger(ClaudeDecisionEngine.class);

    private final ClaudeClient claude;
    private final ClaudePromptBuilder promptBuilder;
    private final ClaudeVerdictParser parser;
    private final DecisionStore decisions;
    private final ObjectMapper objectMapper;

    public ClaudeDecisionEngine(ClaudeClient claude, ClaudePromptBuilder promptBuilder,
                                ClaudeVerdictParser parser, DecisionStore decisions, ObjectMapper objectMapper) {
        this.claude = claude;
        this.promptBuilder = promptBuilder;
        this.parser = parser;
        this.decisions = decisions;
        this.objectMapper = objectMapper;
    }

    @Override
    public Verdict decide(DecisionContext context) {
        String system = promptBuilder.systemPrompt();
        String user = promptBuilder.userPrompt(context);

        String responseText;
        try {
            responseText = claude.complete(system, user);
        } catch (Exception e) {
            String reason = "Claude call failed: " + e.getMessage();
            store(context, user, null, ERROR_ACTION, null, reason);
            throw new DecisionFailedException(reason, e);
        }

        ParsedVerdict parsed;
        try {
            parsed = parser.parse(responseText, context.symbol(), Instant.now());
        } catch (DecisionFailedException e) {
            store(context, user, responseText, ERROR_ACTION, null, e.getMessage());
            throw e;
        }
        store(context, user, responseText, parsed.verdict().action().name(),
                parsed.verdict().confidence(), parsed.whatChanged());
        return parsed.verdict();
    }

    private void store(DecisionContext ctx, String user, String responseText,
                       String action, Double confidence, String note) {
        try {
            String request = objectMapper.writeValueAsString(Map.of("user", user));
            String response = objectMapper.writeValueAsString(Map.of("text", responseText == null ? "" : responseText));
            decisions.record(ctx.symbol(), ctx.triggerReason(), request, response, action, confidence, note);
        } catch (Exception e) {
            LOG.warn("Failed to persist decision for {}: {}", ctx.symbol(), e.getMessage());
        }
    }
}
