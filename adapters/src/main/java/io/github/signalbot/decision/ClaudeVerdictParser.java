package io.github.signalbot.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.signalbot.domain.model.Action;
import io.github.signalbot.domain.model.Verdict;
import io.github.signalbot.domain.port.DecisionFailedException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

/**
 * Parses Claude's response text into a verdict. Pure.
 *
 * <p>Strict: a missing response, text without a JSON object, invalid JSON, an
 * unknown or missing {@code action}, a missing {@code confidence}, a non-numeric
 * number, or a BUY/SELL without a positive {@code quantity} throws
 * {@link DecisionFailedException}. It never substitutes a HOLD, so a real HOLD
 * stays distinguishable from "no usable answer".
 */
@Component
public class ClaudeVerdictParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TTL = Duration.ofMinutes(15);

    public ParsedVerdict parse(String rawText, String symbol, Instant now) {
        if (rawText == null || rawText.isBlank()) {
            throw new DecisionFailedException("empty response from Claude");
        }
        int start = rawText.indexOf('{');
        int end = rawText.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new DecisionFailedException("no JSON object in response: " + abbreviate(rawText));
        }
        JsonNode n;
        try {
            n = MAPPER.readTree(rawText.substring(start, end + 1));
        } catch (Exception e) {
            throw new DecisionFailedException("invalid JSON in response: " + e.getMessage(), e);
        }
        Action action = parseAction(n.path("action"));
        BigDecimal quantity = decimalOrNull(n, "quantity");
        if (action != Action.HOLD && (quantity == null || quantity.signum() <= 0)) {
            throw new DecisionFailedException(action + " without a positive 'quantity'");
        }
        JsonNode confidence = n.path("confidence");
        if (!confidence.isNumber()) {
            throw new DecisionFailedException("missing or non-numeric 'confidence'");
        }
        Verdict verdict = new Verdict(
                symbol,
                action,
                quantity == null ? BigDecimal.ZERO : quantity,
                decimalOrNull(n, "entry"),
                decimalOrNull(n, "stop"),
                decimalOrNull(n, "takeProfit"),
                clampConfidence(confidence.asDouble()),
                n.path("rationale").asText(""),
                now.plus(TTL));
        return new ParsedVerdict(verdict, n.path("whatChanged").asText(""));
    }

    private static Action parseAction(JsonNode node) {
        if (!node.isTextual()) {
            throw new DecisionFailedException("missing 'action'");
        }
        try {
            return Action.valueOf(node.asText().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new DecisionFailedException("unknown action '" + node.asText() + "'");
        }
    }

    private static BigDecimal decimalOrNull(JsonNode parent, String field) {
        JsonNode node = parent.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        String t = node.asText("");
        if (t.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(t);
        } catch (NumberFormatException e) {
            throw new DecisionFailedException("'" + field + "' is not a number: " + t);
        }
    }

    private static double clampConfidence(double c) {
        return Math.max(0.0, Math.min(1.0, c));
    }

    private static String abbreviate(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }
}
