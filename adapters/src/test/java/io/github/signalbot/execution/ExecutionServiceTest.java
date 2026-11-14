package io.github.signalbot.execution;

import io.github.signalbot.application.trigger.ExitGuard;
import io.github.signalbot.application.trigger.TriggerEvaluator;
import io.github.signalbot.application.trigger.TriggerSettings;
import io.github.signalbot.config.ExecutionProperties;
import io.github.signalbot.domain.model.Action;
import io.github.signalbot.domain.model.FeeBreakdown;
import io.github.signalbot.domain.model.PaperFill;
import io.github.signalbot.domain.model.PriceSnapshot;
import io.github.signalbot.domain.model.Verdict;
import io.github.signalbot.domain.port.PaperWallet;
import io.github.signalbot.execution.ExecutionResult.Outcome;
import io.github.signalbot.persistence.ExecutionStore;
import io.github.signalbot.persistence.ExecutionStore.ExecutionRow;
import io.github.signalbot.persistence.PositionGuardStore;
import io.github.signalbot.trigger.ContextAssembler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    private final FeeBreakdown fee = new FeeBreakdown(new BigDecimal("5.25"), new BigDecimal("525"),
            BigDecimal.ZERO, new BigDecimal("530.25"), "us-per-share");

    @SuppressWarnings("unchecked")
    private ExecutionService service(ExecutionMode mode) {
        ObjectProvider<ExecutionGateway> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(gateway);
        ExecutionProperties props = new ExecutionProperties(mode, Duration.ofMinutes(30), Duration.ofSeconds(60), 15000);
        return new ExecutionService(store, paperWallet, provider, props, exitGuard, guardStore,
                triggerEvaluator, assembler, TransactionOperations.withoutTransaction());
    }

    private static Verdict buy() {
        return new Verdict("AAPL", Action.BUY, new BigDecimal("2"), new BigDecimal("150"),
                new BigDecimal("140"), new BigDecimal("170"), 0.8, "test", Instant.now().plusSeconds(900));
    }

    private static ExecutionRow row(ExecutionState state, Instant pushedAt) {
        return new ExecutionRow(1L, "AAPL", Action.BUY, ExecutionSource.VERDICT, new BigDecimal("2"),
                new BigDecimal("140"), new BigDecimal("170"), new BigDecimal("150"), state, pushedAt);
    }

    private static ExecutionRow pendingBuy(Instant pushedAt) {
        return row(ExecutionState.PENDING, pushedAt);
    }

    private void paperFillSucceeds() {
        when(paperWallet.applyAt(any(), any(), any())).thenReturn(new PaperFill("AAPL", Action.BUY,
                new BigDecimal("2"), new BigDecimal("151"), fee, BigDecimal.ZERO, BigDecimal.ZERO, Instant.now()));
    }

    @Test
    void submitInsertsPendingAndPrompts() {
        when(store.insertPending(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(7L);
        long id = service(ExecutionMode.MANUAL).submit(buy(), ExecutionSource.VERDICT, new BigDecimal("150"));
        assertEquals(7L, id);
        verify(gateway).promptExecution(eq(7L), eq("AAPL"), eq(Action.BUY), any(), any());
    }

    @Test
    void fillBuyAppliesPaperOnceWithExecutionIdAndArmsGuard() {
        when(store.find(1L)).thenReturn(Optional.of(pendingBuy(Instant.now().minusSeconds(10))));
        when(store.markFilled(eq(1L), any(), any(), any(), anyLong())).thenReturn(true);
        paperFillSucceeds();

        ExecutionResult result = service(ExecutionMode.MANUAL).fill(1L, new BigDecimal("151"));

        assertEquals(Outcome.FILLED, result.outcome());
        assertEquals("Filled at 151.", result.message());
        verify(paperWallet).applyAt(any(), eq(new BigDecimal("151")), eq(1L));
        verify(store).recordFee(eq(1L), eq(fee));
        verify(guardStore).upsert(eq("AAPL"), eq(new BigDecimal("140")), eq(new BigDecimal("170")));
        assertTrue(exitGuard.onPrice("AAPL", new BigDecimal("139")).isPresent());
    }

    @Test
    void losingTheFillRaceBooksNothing() {
        // Both the Telegram reply and the AUTO sweeper read PENDING; only one UPDATE matches.
        when(store.find(1L)).thenReturn(Optional.of(pendingBuy(Instant.now().minusSeconds(90))),
                Optional.of(row(ExecutionState.FILLED, Instant.now().minusSeconds(90))));
        when(store.markFilled(eq(1L), any(), any(), any(), anyLong())).thenReturn(false);

        ExecutionResult result = service(ExecutionMode.AUTO).fill(1L, new BigDecimal("151"));

        assertEquals(Outcome.NOT_PENDING, result.outcome());
        assertEquals(ExecutionState.FILLED, result.state());
        verify(paperWallet, never()).applyAt(any(), any(), any());
        verify(store, never()).recordFee(anyLong(), any());
        assertFalse(exitGuard.onPrice("AAPL", new BigDecimal("139")).isPresent());
    }

    @Test
    void secondFillOfSameExecutionIsIgnored() {
        when(store.find(1L)).thenReturn(Optional.of(pendingBuy(Instant.now().minusSeconds(10))),
                Optional.of(row(ExecutionState.FILLED, Instant.now().minusSeconds(10))));
        when(store.markFilled(eq(1L), any(), any(), any(), anyLong())).thenReturn(true);
        paperFillSucceeds();
        ExecutionService service = service(ExecutionMode.MANUAL);

        assertEquals(Outcome.FILLED, service.fill(1L, new BigDecimal("151")).outcome());
        ExecutionResult second = service.fill(1L, new BigDecimal("152"));

        assertEquals(Outcome.NOT_PENDING, second.outcome());
        verify(paperWallet, times(1)).applyAt(any(), any(), any());
    }

    @Test
    void skipAfterFillDoesNotClaimSkipped() {
        when(store.markSkipped(eq(1L), any(), any())).thenReturn(false);
        when(store.find(1L)).thenReturn(Optional.of(row(ExecutionState.FILLED, Instant.now())));

        ExecutionResult result = service(ExecutionMode.MANUAL).skip(1L, "skipped via Telegram");

        assertEquals(Outcome.NOT_PENDING, result.outcome());
        assertFalse(result.message().startsWith("Skipped"));
        assertTrue(result.message().contains("already FILLED"));
    }

    @Test
    void skipPendingMarksSkipped() {
        when(store.markSkipped(eq(5L), eq("not now"), any())).thenReturn(true);
        assertEquals("Skipped.", service(ExecutionMode.MANUAL).skip(5L, "not now").message());
    }

    @Test
    void paperRejectionRollsBackAndParksAsFailed() {
        when(store.find(1L)).thenReturn(Optional.of(pendingBuy(Instant.now().minusSeconds(10))));
        when(store.markFilled(eq(1L), any(), any(), any(), anyLong())).thenReturn(true);
        when(paperWallet.applyAt(any(), any(), any())).thenThrow(new IllegalStateException("No position to sell"));
        when(store.markFailed(eq(1L), any(), any())).thenReturn(true);

        ExecutionResult result = service(ExecutionMode.MANUAL).fill(1L, new BigDecimal("151"));

        assertEquals(Outcome.FAILED, result.outcome());
        assertTrue(result.message().contains("No position to sell"));
        verify(store).markFailed(eq(1L), eq("No position to sell"), any());
        assertFalse(exitGuard.onPrice("AAPL", new BigDecimal("139")).isPresent());
    }

    @Test
    void okWithoutLastPriceKeepsPending() {
        when(store.find(1L)).thenReturn(Optional.of(pendingBuy(Instant.now())));
        when(assembler.snapshot("AAPL")).thenReturn(null);
        assertEquals(Outcome.NO_PRICE, service(ExecutionMode.MANUAL).fillAtLastPrice(1L).outcome());
        verify(store, never()).markFilled(anyLong(), any(), any(), any(), anyLong());
    }

    @Test
    void autoSweepFillsAtLastPrice() {
        ExecutionRow pending = pendingBuy(Instant.now().minusSeconds(120));
        when(store.findPending()).thenReturn(List.of(pending));
        when(store.find(1L)).thenReturn(Optional.of(pending));
        when(assembler.snapshot("AAPL")).thenReturn(
                new PriceSnapshot("AAPL", new BigDecimal("151"), new BigDecimal("150"), Instant.now()));
        when(store.markFilled(eq(1L), any(), any(), any(), anyLong())).thenReturn(true);
        paperFillSucceeds();

        service(ExecutionMode.AUTO).sweep();

        verify(paperWallet).applyAt(any(), eq(new BigDecimal("151")), eq(1L));
    }

    @Test
    void sweepExpiresStalePendingInManualMode() {
        when(store.findPending()).thenReturn(List.of(pendingBuy(Instant.now().minus(Duration.ofMinutes(40)))));
        service(ExecutionMode.MANUAL).sweep();
        verify(store).markExpired(eq(1L), any());
        verify(paperWallet, never()).applyAt(any(), any(), any());
    }
}
