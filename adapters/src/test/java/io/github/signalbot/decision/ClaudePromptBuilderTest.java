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
                new PriceSnapshot("AAPL", new BigDecimal("150"), new BigDecimal("145"), Instant.now()), null);
        String u = builder.userPrompt(ctx);
        assertTrue(u.contains("AAPL"));
        assertTrue(u.contains("price moved 3.5%"));
        assertTrue(u.contains("150"));
    }
}
