package io.github.signalbot.trigger;

import io.github.signalbot.application.risk.RiskLimitEvaluator;
import io.github.signalbot.application.trigger.TriggerSignal;
import io.github.signalbot.application.trigger.TriggerType;
import io.github.signalbot.config.FxConverter;
import io.github.signalbot.domain.model.DecisionContext;
import io.github.signalbot.domain.port.DecisionEngine;
import io.github.signalbot.domain.port.DecisionFailedException;
import io.github.signalbot.domain.port.Notifier;
import io.github.signalbot.domain.port.SourceFailureException;
import io.github.signalbot.execution.ExecutionService;
import io.github.signalbot.persistence.DailyPnlStore;
import io.github.signalbot.persistence.TradeQueryStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LiveDecisionHandlerTest {

    private final DecisionEngine engine = mock(DecisionEngine.class);
    private final ContextAssembler assembler = mock(ContextAssembler.class);
    private final Notifier notifier = mock(Notifier.class);
    private final ExecutionService executions = mock(ExecutionService.class);
    private final TriggerSignal signal = new TriggerSignal("AAPL", TriggerType.values()[0], "news", Instant.now());

    @SuppressWarnings("unchecked")
    private LiveDecisionHandler handler() {
        ObjectProvider<DecisionEngine> engines = mock(ObjectProvider.class);
        when(engines.getIfAvailable()).thenReturn(engine);
        ObjectProvider<Notifier> notifiers = mock(ObjectProvider.class);
        when(notifiers.getIfAvailable()).thenReturn(notifier);
        return new LiveDecisionHandler(engines, assembler, mock(RiskLimitEvaluator.class), notifiers,
                mock(TradeQueryStore.class), mock(DailyPnlStore.class), mock(FxConverter.class), executions);
    }

    @Test
    void sourceFailureAlertsAndSkipsDecision() {
        when(assembler.assemble(any(), any(), any()))
                .thenThrow(new SourceFailureException("finnhub/company-news", "expected a JSON array"));
        handler().handle(signal);
        verify(engine, never()).decide(any());
        verify(notifier).alert(eq("AAPL"), contains("finnhub/company-news"));
        verify(notifier, never()).send(any(), any());
    }

    @Test
    void decisionFailureAlertsInsteadOfHold() {
        when(assembler.assemble(any(), any(), any()))
                .thenReturn(new DecisionContext("AAPL", "news", null, List.of(), List.of(), null, null, true));
        when(engine.decide(any())).thenThrow(new DecisionFailedException("no JSON object in response"));
        handler().handle(signal);
        verify(notifier).alert(eq("AAPL"), contains("decision failed"));
        verify(notifier, never()).send(any(), any());
        verify(executions, never()).submit(any(), any(), any());
    }
}
