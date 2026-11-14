package io.github.signalbot.decision;

import io.github.signalbot.domain.model.Action;
import io.github.signalbot.domain.port.DecisionFailedException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaudeVerdictParserTest {

    private static final Instant NOW = Instant.parse("2026-10-23T15:00:00Z");
    private final ClaudeVerdictParser parser = new ClaudeVerdictParser();

    @Test
    void parsesBuyVerdict() {
        String json = "{\"action\":\"BUY\",\"quantity\":3,\"entry\":150,\"stop\":140,"
                + "\"takeProfit\":170,\"confidence\":0.8,\"rationale\":\"momentum\",\"whatChanged\":\"news\"}";
        ParsedVerdict p = parser.parse(json, "AAPL", NOW);
        assertEquals(Action.BUY, p.verdict().action());
        assertEquals(0, p.verdict().quantity().compareTo(new BigDecimal("3")));
        assertEquals("news", p.whatChanged());
    }

    @Test
    void extractsJsonEmbeddedInProse() {
        String text = "Here is my answer:\n{\"action\":\"SELL\",\"quantity\":1,\"confidence\":0.6,"
                + "\"rationale\":\"take profit\",\"whatChanged\":\"up 5%\"}\nThanks.";
        assertEquals(Action.SELL, parser.parse(text, "AAPL", NOW).verdict().action());
    }

    @Test
    void realHoldIsStillHold() {
        String json = "{\"action\":\"HOLD\",\"quantity\":0,\"confidence\":0.4,\"whatChanged\":\"nothing\"}";
        assertEquals(Action.HOLD, parser.parse(json, "AAPL", NOW).verdict().action());
    }

    @Test
    void noJsonFailsInsteadOfHold() {
        var e = assertThrows(DecisionFailedException.class, () -> parser.parse("I cannot decide.", "AAPL", NOW));
        assertTrue(e.getMessage().contains("no JSON"));
    }

    @Test
    void nullOrBlankResponseFails() {
        assertThrows(DecisionFailedException.class, () -> parser.parse(null, "AAPL", NOW));
        assertThrows(DecisionFailedException.class, () -> parser.parse("  ", "AAPL", NOW));
    }

    @Test
    void brokenJsonFails() {
        assertThrows(DecisionFailedException.class,
                () -> parser.parse("{\"action\":\"BUY\",\"quantity\":}", "AAPL", NOW));
    }

    @Test
    void unknownOrMissingActionFails() {
        var e = assertThrows(DecisionFailedException.class,
                () -> parser.parse("{\"action\":\"MAYBE\",\"quantity\":0,\"confidence\":0.5}", "AAPL", NOW));
        assertTrue(e.getMessage().contains("MAYBE"));
        assertThrows(DecisionFailedException.class,
                () -> parser.parse("{\"quantity\":0,\"confidence\":0.5}", "AAPL", NOW));
    }

    @Test
    void tradeWithoutPositiveQuantityFails() {
        assertThrows(DecisionFailedException.class,
                () -> parser.parse("{\"action\":\"BUY\",\"confidence\":0.7}", "AAPL", NOW));
        assertThrows(DecisionFailedException.class,
                () -> parser.parse("{\"action\":\"SELL\",\"quantity\":0,\"confidence\":0.7}", "AAPL", NOW));
    }

    @Test
    void missingConfidenceOrNonNumericFieldFails() {
        assertThrows(DecisionFailedException.class,
                () -> parser.parse("{\"action\":\"HOLD\",\"quantity\":0}", "AAPL", NOW));
        assertThrows(DecisionFailedException.class,
                () -> parser.parse("{\"action\":\"BUY\",\"quantity\":2,\"stop\":\"low\",\"confidence\":0.7}", "AAPL", NOW));
    }

    @Test
    void confidenceIsClamped() {
        String json = "{\"action\":\"HOLD\",\"quantity\":0,\"confidence\":9.9,\"whatChanged\":\"\"}";
        assertEquals(1.0, parser.parse(json, "AAPL", NOW).verdict().confidence());
    }
}
