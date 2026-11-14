package io.github.signalbot.execution;

import io.github.signalbot.application.trigger.ExitGuard;
import io.github.signalbot.application.trigger.TriggerEvaluator;
import io.github.signalbot.application.trigger.TriggerSettings;
import io.github.signalbot.config.ExecutionProperties;
import io.github.signalbot.domain.model.Action;
import io.github.signalbot.domain.model.FeeBreakdown;
import io.github.signalbot.domain.model.PaperFill;
import io.github.signalbot.domain.model.Verdict;
import io.github.signalbot.domain.port.PaperWallet;
import io.github.signalbot.persistence.ExecutionStore;
import io.github.signalbot.persistence.ExecutionStore.ExecutionRow;
import io.github.signalbot.persistence.PositionGuardStore;
import io.github.signalbot.trigger.ContextAssembler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExecutionServiceTest {

    private final ExecutionStore store = mock(ExecutionStore.class);
    private final PaperWallet paperWallet = mock(PaperWallet.class);
    private final PositionGuardStore guardStore = mock(PositionGuardStore.class);
    private final ContextAssembler assembler = mock(ContextAssembler.class);
    private final ExecutionGateway gateway = mock(ExecutionGateway.class);
    private final ExitGuard exitGuard = new ExitGuard();
    private final TriggerEvaluator triggerEvaluator =
            new TriggerEvaluator(new TriggerSettings(new BigDecimal("0.03"), Duration.ofMinutes(15)));

    @SuppressWarnings("unchecked")
    private ExecutionService service(ExecutionMode mode) {
        ObjectProvider<ExecutionGateway> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(gateway);
        ExecutionProperties props = new ExecutionProperties(mode, Duration.ofMinutes(30), Duration.ofSeconds(60), 15000);
        return new ExecutionService(store, paperWallet, provider, props, exitGuard, guardStore,
                triggerEvaluator, assembler);
    }

    private static Verdict buy() {
        return new Verdict("AAPL", Action.BUY, new BigDecimal("2"), new BigDecimal("150"),
                new BigDecimal("140"), new BigDecimal("170"), 0.8, "test", Instant.now().plusSeconds(900));
    }

    private static ExecutionRow pendingBuy(Instant pushedAt) {
        return new ExecutionRow(1L, "AAPL", Action.BUY, ExecutionSource.VERDICT, new BigDecimal("2"),
                new BigDecimal("140"), new BigDecimal("170"), new BigDecimal("150"),
                ExecutionState.PENDING, pushedAt);
    }

    @Test
    void submitInsertsPendingAndPrompts() {
        when(store.insertPending(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(7L);
        long id = service(ExecutionMode.MANUAL).submit(buy(), ExecutionSource.VERDICT, new BigDecimal("150"));
        assertEquals(7L, id);
        verify(gateway).promptExecution(eq(7L), eq("AAPL"), eq(Action.BUY), any(), any());
    }

    @Test
    void fillBuyAppliesPaperAndArmsGuard() {
        when(store.find(1L)).thenReturn(Optional.of(pendingBuy(Instant.now().minusSeconds(10))));
        FeeBreakdown fee = new FeeBreakdown(new BigDecimal("5.25"), new BigDecimal("525"),
                BigDecimal.ZERO, new BigDecimal("530.25"), "us-per-share");
        when(paperWallet.applyAt(any(), any())).thenReturn(new PaperFill("AAPL", Action.BUY,
                new BigDecimal("2"), new BigDecimal("151"), fee, BigDecimal.ZERO, BigDecimal.ZERO, Instant.now()));
        service(ExecutionMode.MANUAL).fill(1L, new BigDecimal("151"));
        verify(store).markFilled(eq(1L), eq(new BigDecimal("151")), any(), any(), org.mockito.ArgumentMatchers.anyLong());
        verify(paperWallet).applyAt(any(), eq(new BigDecimal("151")));
        verify(store).recordFee(eq(1L), eq(fee));
        verify(guardStore).upsert(eq("AAPL"), eq(new BigDecimal("140")), eq(new BigDecimal("170")));
        // Guard is now armed: a price below the stop fires an exit.
        assertTrue(exitGuard.onPrice("AAPL", new BigDecimal("139")).isPresent());
    }

    @Test
    void skipMarksSkipped() {
        service(ExecutionMode.MANUAL).skip(5L, "not now");
        verify(store).markSkipped(eq(5L), eq("not now"), any());
    }

    @Test
    void sweepExpiresStalePendingInManualMode() {
        when(store.findPending()).thenReturn(List.of(pendingBuy(Instant.now().minus(Duration.ofMinutes(40)))));
        service(ExecutionMode.MANUAL).sweep();
        verify(store).markExpired(eq(1L), any());
        verify(paperWallet, never()).applyAt(any(), any());
    }
}
