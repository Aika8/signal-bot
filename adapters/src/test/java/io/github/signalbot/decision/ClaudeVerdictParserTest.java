package io.github.signalbot.decision;

import io.github.signalbot.domain.model.Action;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void defaultsToHoldWhenNoJson() {
        assertEquals(Action.HOLD, parser.parse("I cannot decide.", "AAPL", NOW).verdict().action());
    }

    @Test
    void unknownActionBecomesHold() {
        assertEquals(Action.HOLD,
                parser.parse("{\"action\":\"MAYBE\",\"quantity\":0}", "AAPL", NOW).verdict().action());
    }

    @Test
    void confidenceIsClamped() {
        String json = "{\"action\":\"HOLD\",\"quantity\":0,\"confidence\":9.9,\"whatChanged\":\"\"}";
        assertEquals(1.0, parser.parse(json, "AAPL", NOW).verdict().confidence());
    }

    @Test
    void nullResponseIsHold() {
        assertEquals(Action.HOLD, parser.parse(null, "AAPL", NOW).verdict().action());
    }
}
