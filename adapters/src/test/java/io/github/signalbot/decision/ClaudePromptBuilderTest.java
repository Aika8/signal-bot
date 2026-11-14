package io.github.signalbot.decision;

import io.github.signalbot.domain.model.DecisionContext;
import io.github.signalbot.domain.model.PriceSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaudePromptBuilderTest {

    private final ClaudePromptBuilder builder = new ClaudePromptBuilder();

    @Test
    void systemPromptRequiresJsonHoldDefaultAndWhatChanged() {
        String s = builder.systemPrompt();
        assertTrue(s.contains("whatChanged"));
        assertTrue(s.contains("HOLD"));
        assertTrue(s.contains("JSON"));
    }

    @Test
    void userPromptIncludesSymbolTriggerAndPrice() {
        DecisionContext ctx = new DecisionContext(
                "AAPL", "price moved 3.5% within 15m", null, List.of(), List.of(),
                new PriceSnapshot("AAPL", new BigDecimal("150"), new BigDecimal("145"), Instant.now()), null, true);
        String u = builder.userPrompt(ctx);
        assertTrue(u.contains("AAPL"));
        assertTrue(u.contains("price moved 3.5%"));
        assertTrue(u.contains("150"));
    }

    @Test
    void emptySectionsAreExplicitNotOmitted() {
        DecisionContext none = new DecisionContext("AAPL", "news", null, List.of(), List.of(), null, null, true);
        String u = builder.userPrompt(none);
        assertTrue(u.contains("Positions: not available"));
        assertTrue(u.contains("Cash: none"));
        assertTrue(u.contains("Recent news (24h): none"));
        assertTrue(u.contains("Last price: not available"));

        DecisionContext noFeed = new DecisionContext("AAPL", "news", null, List.of(), List.of(), null, null, false);
        assertTrue(builder.userPrompt(noFeed).contains("Recent news (24h): not available"));
    }
}
