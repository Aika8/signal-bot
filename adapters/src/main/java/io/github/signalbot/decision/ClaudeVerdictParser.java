package io.github.signalbot.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.signalbot.domain.model.Action;
import io.github.signalbot.domain.model.Verdict;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

/**
 * Parses Claude's response text into a verdict. Tolerant: any missing/invalid
 * field or non-JSON text falls back to a safe HOLD. Pure.
 */
@Component
public class ClaudeVerdictParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TTL = Duration.ofMinutes(15);

    public ParsedVerdict parse(String rawText, String symbol, Instant now) {
        Verdict hold = hold(symbol, now);
        if (rawText == null) {
            return new ParsedVerdict(hold, "no response");
        }
        int start = rawText.indexOf('{');
        int end = rawText.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return new ParsedVerdict(hold, "no JSON in response");
        }
        try {
            JsonNode n = MAPPER.readTree(rawText.substring(start, end + 1));
            Action action = parseAction(n.path("action").asText("HOLD"));
            Verdict verdict = new Verdict(
                    symbol,
                    action,
                    decimal(n.path("quantity")),
                    decimalOrNull(n.path("entry")),
                    decimalOrNull(n.path("stop")),
                    decimalOrNull(n.path("takeProfit")),
                    clampConfidence(n.path("confidence").asDouble(0.0)),
                    n.path("rationale").asText(""),
                    now.plus(TTL));
            return new ParsedVerdict(verdict, n.path("whatChanged").asText(""));
        } catch (Exception e) {
            return new ParsedVerdict(hold, "parse error: " + e.getMessage());
        }
    }

    private static Verdict hold(String symbol, Instant now) {
        return new Verdict(symbol, Action.HOLD, BigDecimal.ZERO, null, null, null,
                0.0, "default hold", now.plus(TTL));
    }

    private static Action parseAction(String raw) {
        try {
            return Action.valueOf(raw.trim().toUpperCase());
        } catch (Exception e) {
            return Action.HOLD;
        }
    }

    private static BigDecimal decimal(JsonNode node) {
        String t = node.asText("");
        return t.isBlank() ? BigDecimal.ZERO : new BigDecimal(t);
    }

    private static BigDecimal decimalOrNull(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        String t = node.asText("");
        return t.isBlank() ? null : new BigDecimal(t);
    }

    private static double clampConfidence(double c) {
        return Math.max(0.0, Math.min(1.0, c));
    }
}
