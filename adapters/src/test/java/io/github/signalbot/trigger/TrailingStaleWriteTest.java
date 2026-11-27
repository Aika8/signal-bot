package io.github.signalbot.trigger;

import io.github.signalbot.application.trigger.ExitGuard;
import io.github.signalbot.application.trigger.TrailingLevels;
import io.github.signalbot.application.trigger.TrailingSnapshot;
import io.github.signalbot.application.trigger.TrailingState;
import io.github.signalbot.persistence.PositionGuardStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** SB-033 (#30): the writer passes the levels it read together with the state, so the store can refuse a stale one. */
class TrailingStaleWriteTest {

    private static final TrailingLevels OLD = new TrailingLevels(bd("108"), bd("2"), bd("0.2"));
    private static final TrailingLevels NEW = new TrailingLevels(bd("55"), bd("1.5"), bd("0.15"));

    private final ExitGuard guard = new ExitGuard();
    private final PositionGuardStore store = mock(PositionGuardStore.class);
    private final TrailingStateWriter writer = new TrailingStateWriter(guard, store);

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    @Test
    void snapshotReturnsTheLevelsAndTheStateOfOneArmedGuard() {
        guard.armTrailing("AAPL", bd("96"), OLD);
        guard.onPrice("AAPL", bd("110"));

        assertEquals(Optional.of(new TrailingSnapshot(OLD, bd("110"), true)), guard.snapshot("AAPL"));

        guard.armTrailing("AAPL", bd("50"), NEW);
        assertEquals(Optional.of(new TrailingSnapshot(NEW, null, false)), guard.snapshot("AAPL"));

        guard.arm("AAPL", bd("50"), null);
        assertTrue(guard.snapshot("AAPL").isEmpty(), "no trailing armed");
        assertTrue(guard.snapshot("MSFT").isEmpty(), "not armed at all");
    }

    @Test
    void theWriterPassesTheLevelsItReadWithTheState() {
        when(store.saveTrailingState(anyString(), any(TrailingSnapshot.class))).thenReturn(true);
        guard.armTrailing("AAPL", bd("96"), OLD);

        guard.onPrice("AAPL", bd("108"));
        writer.afterPrice("AAPL");

        ArgumentCaptor<TrailingSnapshot> written = ArgumentCaptor.forClass(TrailingSnapshot.class);
        verify(store).saveTrailingState(eq("AAPL"), written.capture());
        assertEquals(new TrailingSnapshot(OLD, bd("108"), true), written.getValue());
        verify(store, never()).saveTrailingState(anyString(), any(TrailingState.class));
    }

    @Test
    void aStateTheStoreRefusesIsNotRetriedOnEveryTick() {
        when(store.saveTrailingState(anyString(), any(TrailingSnapshot.class))).thenReturn(false);
        guard.armTrailing("AAPL", bd("96"), OLD);

        guard.onPrice("AAPL", bd("108"));
        writer.afterPrice("AAPL");
        guard.onPrice("AAPL", bd("107"));
        writer.afterPrice("AAPL");

        verify(store, times(1)).saveTrailingState(anyString(), any(TrailingSnapshot.class));

        guard.onPrice("AAPL", bd("109"));
        writer.afterPrice("AAPL");
        verify(store, times(2)).saveTrailingState(anyString(), any(TrailingSnapshot.class));
    }

    @Test
    void afterARearmTheNextActivationIsWrittenWithTheNewLevels() {
        when(store.saveTrailingState(anyString(), any(TrailingSnapshot.class))).thenReturn(true);
        guard.armTrailing("AAPL", bd("96"), OLD);
        guard.onPrice("AAPL", bd("110"));
        writer.afterPrice("AAPL");

        guard.armTrailing("AAPL", bd("50"), NEW);
        writer.afterPrice("AAPL");
        guard.onPrice("AAPL", bd("56"));
        writer.afterPrice("AAPL");

        ArgumentCaptor<TrailingSnapshot> written = ArgumentCaptor.forClass(TrailingSnapshot.class);
        verify(store, times(2)).saveTrailingState(eq("AAPL"), written.capture());
        assertEquals(new TrailingSnapshot(NEW, bd("56"), true), written.getAllValues().get(1));
    }
}
