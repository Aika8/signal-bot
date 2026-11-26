package io.github.signalbot.execution;

import io.github.signalbot.application.risk.StopSource;
import io.github.signalbot.application.trigger.ExitGuard;
import io.github.signalbot.application.trigger.TriggerEvaluator;
import io.github.signalbot.application.trigger.TriggerSettings;
import io.github.signalbot.config.ExecutionProperties;
import io.github.signalbot.domain.model.Action;
import io.github.signalbot.domain.model.Verdict;
import io.github.signalbot.domain.port.PaperWallet;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** SB-008: a submitted execution hands its sizing facts to the store; the 3-argument submit hands none. */
class ExecutionSizingTest {

    private final ExecutionStore store = mock(ExecutionStore.class);
    private final ExecutionGateway gateway = mock(ExecutionGateway.class);

    private static final SizingInfo SIZING = new SizingInfo(StopSource.ATR, new BigDecimal("120"),
            new BigDecimal("5"), new BigDecimal("10"), new BigDecimal("52500"), new BigDecimal("3"));

    @SuppressWarnings("unchecked")
    private ExecutionService service() {
        ObjectProvider<ExecutionGateway> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(gateway);
        ExecutionProperties props = new ExecutionProperties(ExecutionMode.MANUAL, Duration.ofMinutes(30),
                Duration.ofSeconds(60), 15000);
        return new ExecutionService(store, mock(PaperWallet.class), provider, props, new ExitGuard(),
                mock(PositionGuardStore.class),
                new TriggerEvaluator(new TriggerSettings(new BigDecimal("0.03"), Duration.ofMinutes(15))),
                mock(ContextAssembler.class), TransactionOperations.withoutTransaction());
    }

    private static Verdict buy() {
        return new Verdict("AAPL", Action.BUY, new BigDecimal("5"), new BigDecimal("150"),
                new BigDecimal("140"), new BigDecimal("170"), 0.8, "test", Instant.now().plusSeconds(900));
    }

    @Test
    void submitWithSizingStoresTheSizingFacts() {
        when(store.insertPending(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(9L);

        long id = service().submit(buy(), ExecutionSource.VERDICT, new BigDecimal("150"), SIZING);

        assertEquals(9L, id);
        verify(store).insertPending(eq("AAPL"), eq(Action.BUY), eq(ExecutionSource.VERDICT), eq(new BigDecimal("5")),
                eq(new BigDecimal("140")), eq(new BigDecimal("170")), eq(new BigDecimal("150")), any(), eq(SIZING));
        verify(gateway).promptExecution(eq(9L), eq("AAPL"), eq(Action.BUY), any(), any());
    }

    @Test
    void submitTheOldWayStoresNoSizing() {
        when(store.insertPending(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(4L);

        long id = service().submit(buy(), ExecutionSource.EXIT, new BigDecimal("150"));

        assertEquals(4L, id);
        verify(store, never()).insertPending(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void submitWithNullSizingStoresNoSizing() {
        when(store.insertPending(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(5L);

        assertEquals(5L, service().submit(buy(), ExecutionSource.VERDICT, new BigDecimal("150"), null));
        verify(store, never()).insertPending(any(), any(), any(), any(), any(), any(), any(), any(), isNull());
    }

    @Test
    void rowWithoutSizingHasNullSizing() {
        ExecutionRow row = new ExecutionRow(1L, "AAPL", Action.SELL, ExecutionSource.VERDICT, BigDecimal.ONE,
                null, null, new BigDecimal("150"), ExecutionState.PENDING, Instant.now());
        assertNull(row.sizing());
    }
}
