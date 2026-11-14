package io.github.signalbot.decision;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.signalbot.domain.model.Action;
import io.github.signalbot.domain.model.DecisionContext;
import io.github.signalbot.domain.port.DecisionFailedException;
import io.github.signalbot.persistence.DecisionStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClaudeDecisionEngineTest {

    private final ClaudeClient claude = mock(ClaudeClient.class);
    private final DecisionStore store = mock(DecisionStore.class);
    private final ClaudeDecisionEngine engine = new ClaudeDecisionEngine(
            claude, new ClaudePromptBuilder(), new ClaudeVerdictParser(), store, new ObjectMapper());
    private final DecisionContext ctx =
            new DecisionContext("AAPL", "news", null, List.of(), List.of(), null, null, true);

    @Test
    void callFailureIsRecordedAsErrorAndThrown() {
        when(claude.complete(any(), any())).thenThrow(new RuntimeException("503 overloaded"));
        var e = assertThrows(DecisionFailedException.class, () -> engine.decide(ctx));
        assertEquals("Claude call failed: 503 overloaded", e.getMessage());
        verify(store).record(eq("AAPL"), eq("news"), any(), any(), eq("ERROR"), isNull(), contains("503"));
    }

    @Test
    void unparseableResponseIsRecordedAsErrorAndThrown() {
        when(claude.complete(any(), any())).thenReturn("Sorry, I can't help with that.");
        assertThrows(DecisionFailedException.class, () -> engine.decide(ctx));
        verify(store).record(eq("AAPL"), eq("news"), any(), any(), eq("ERROR"), isNull(), contains("no JSON"));
    }

    @Test
    void realHoldIsReturnedAndRecordedAsHold() {
        when(claude.complete(any(), any()))
                .thenReturn("{\"action\":\"HOLD\",\"quantity\":0,\"confidence\":0.3,\"whatChanged\":\"nothing\"}");
        assertEquals(Action.HOLD, engine.decide(ctx).action());
        verify(store).record(eq("AAPL"), eq("news"), any(), any(), eq("HOLD"), eq(0.3), eq("nothing"));
    }
}
