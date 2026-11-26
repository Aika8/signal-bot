package io.github.signalbot.trigger;

import io.github.signalbot.application.risk.StopSource;
import io.github.signalbot.application.trigger.ExitGuard;
import io.github.signalbot.application.trigger.ExitSignal;
import io.github.signalbot.application.trigger.ExitType;
import io.github.signalbot.application.trigger.TrailingLevels;
import io.github.signalbot.application.trigger.TrailingState;
import io.github.signalbot.application.trigger.TriggerEvaluator;
import io.github.signalbot.application.trigger.TriggerSettings;
import io.github.signalbot.config.ExecutionProperties;
import io.github.signalbot.domain.model.Action;
import io.github.signalbot.domain.model.FeeBreakdown;
import io.github.signalbot.domain.model.PaperFill;
import io.github.signalbot.domain.port.PaperWallet;
import io.github.signalbot.execution.ExecutionGateway;
import io.github.signalbot.execution.ExecutionMode;
import io.github.signalbot.execution.ExecutionResult.Outcome;
import io.github.signalbot.execution.ExecutionService;
import io.github.signalbot.execution.ExecutionSource;
import io.github.signalbot.execution.ExecutionState;
import io.github.signalbot.execution.SizingInfo;
import io.github.signalbot.persistence.ExecutionStore;
import io.github.signalbot.persistence.ExecutionStore.ExecutionRow;
import io.github.signalbot.persistence.PositionGuardStore;
import io.github.signalbot.persistence.PositionGuardStore.StoredGuard;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** SB-013 (D-004): the trailing take-profit is armed at a BUY fill, persisted, and restored after a restart. */
class TrailingRestartTest {

    private final ExecutionStore executionStore = mock(ExecutionStore.class);
    private final PaperWallet paperWallet = mock(PaperWallet.class);
    private final InMemoryGuardStore guardStore = new InMemoryGuardStore();
    private final ExitGuard exitGuard = new ExitGuard();
    private final TrailingStateWriter writer = new TrailingStateWriter(exitGuard, guardStore);

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    @SuppressWarnings("unchecked")
    private ExecutionService service() {
        ObjectProvider<ExecutionGateway> provider = mock(ObjectProvider.class);
        ExecutionProperties props = new ExecutionProperties(ExecutionMode.MANUAL, Duration.ofMinutes(30),
                Duration.ofSeconds(60), 15000);
        TriggerEvaluator evaluator =
                new TriggerEvaluator(new TriggerSettings(bd("0.03"), Duration.ofMinutes(15)));
        return new ExecutionService(executionStore, paperWallet, provider, props, exitGuard, guardStore,
                evaluator, mock(ContextAssembler.class), TransactionOperations.withoutTransaction());
    }

    private void pendingBuy(SizingInfo sizing) {
        ExecutionRow row = new ExecutionRow(1L, "AAPL", Action.BUY, ExecutionSource.VERDICT, bd("5"),
                bd("96"), bd("130"), bd("100"), ExecutionState.PENDING, Instant.now().minusSeconds(5), sizing);
        when(executionStore.find(1L)).thenReturn(Optional.of(row));
        when(executionStore.markFilled(eq(1L), any(), any(), any(), anyLong())).thenReturn(true);
        FeeBreakdown fee = new FeeBreakdown(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "t");
        when(paperWallet.applyAt(any(), any(), any())).thenReturn(new PaperFill("AAPL", Action.BUY, bd("5"),
                bd("100"), fee, BigDecimal.ZERO, BigDecimal.ZERO, Instant.now()));
    }

    private static SizingInfo sizingWithAtr(String atr) {
        return new SizingInfo(StopSource.ATR, null, atr == null ? null : bd(atr), bd("4"), bd("10000.00"), null);
    }

    private Optional<ExitSignal> tick(ExitGuard guard, TrailingStateWriter w, String price) {
        Optional<ExitSignal> signal = guard.onPrice("AAPL", bd(price));
        w.afterPrice("AAPL");
        return signal;
    }

    private static void assertSameValue(String expected, BigDecimal actual) {
        assertEquals(0, bd(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    @Test
    void buyFillWithAtrArmsAndPersistsTheTrailingLevels() {
        pendingBuy(sizingWithAtr("2"));

        assertEquals(Outcome.FILLED, service().fill(1L, bd("100")).outcome());

        StoredGuard stored = guardStore.rows.get("AAPL");
        assertSameValue("96", stored.stop());
        assertSameValue("108", stored.trailing().activation());
        assertSameValue("2", stored.trailing().offset());
        assertSameValue("0.2", stored.trailing().spread());
        assertSameValue("10000", stored.riskKzt());
        assertNull(stored.best());
        assertFalse(stored.activated());
        assertEquals(Optional.of(new TrailingState(null, false)), exitGuard.state("AAPL"));
        // Claude's take-profit (130) is kept on the execution but not armed.
        assertTrue(exitGuard.onPrice("AAPL", bd("131")).isEmpty());
    }

    @Test
    void buyFillWithoutAtrArmsTheStopOnly() {
        pendingBuy(sizingWithAtr(null));

        service().fill(1L, bd("100"));

        assertTrue(exitGuard.state("AAPL").isEmpty());
        assertNull(guardStore.rows.get("AAPL").trailing());
        assertTrue(exitGuard.onPrice("AAPL", bd("131")).isEmpty());
        assertEquals(ExitType.EXIT_STOP, exitGuard.onPrice("AAPL", bd("95")).orElseThrow().type());
    }

    @Test
    void writerSavesOnlyOnActivationAndOnANewBest() {
        pendingBuy(sizingWithAtr("2"));
        service().fill(1L, bd("100"));

        tick(exitGuard, writer, "105");
        tick(exitGuard, writer, "107.99");
        assertEquals(0, guardStore.stateWrites.size());

        tick(exitGuard, writer, "108");
        tick(exitGuard, writer, "107");
        tick(exitGuard, writer, "108");
        tick(exitGuard, writer, "110");
        tick(exitGuard, writer, "109");

        assertEquals(List.of(new TrailingState(bd("108"), true), new TrailingState(bd("110"), true)),
                guardStore.stateWrites);
        assertSameValue("110", guardStore.rows.get("AAPL").best());
        assertTrue(guardStore.rows.get("AAPL").activated());
    }

    @Test
    void afterARestartAnActivatedGuardKeepsItsBestPrice() {
        pendingBuy(sizingWithAtr("2"));
        service().fill(1L, bd("100"));
        tick(exitGuard, writer, "108");
        tick(exitGuard, writer, "115");

        // "Restart": a new guard, writer and loader over the same storage.
        ExitGuard restarted = new ExitGuard();
        TrailingStateWriter restartedWriter = new TrailingStateWriter(restarted, guardStore);
        new GuardLoader(restarted, guardStore, restartedWriter).run(null);

        assertEquals(Optional.of(new TrailingState(bd("115"), true)), restarted.state("AAPL"));
        int writesBefore = guardStore.stateWrites.size();
        assertTrue(tick(restarted, restartedWriter, "114").isEmpty());
        assertEquals(writesBefore, guardStore.stateWrites.size(), "an unchanged state is not rewritten");

        ExitSignal exit = tick(restarted, restartedWriter, "112.9").orElseThrow();
        assertEquals(ExitType.EXIT_TRAIL, exit.type());
        assertSameValue("113", exit.level());
        assertSameValue("112.7", exit.suggestedLimit());
    }

    @Test
    void afterARestartAGuardNotYetActivatedStaysNotActivated() {
        pendingBuy(sizingWithAtr("2"));
        service().fill(1L, bd("100"));
        tick(exitGuard, writer, "105");

        ExitGuard restarted = new ExitGuard();
        new GuardLoader(restarted, guardStore, new TrailingStateWriter(restarted, guardStore)).run(null);

        assertEquals(Optional.of(new TrailingState(null, false)), restarted.state("AAPL"));
        assertTrue(restarted.onPrice("AAPL", bd("108")).isEmpty());
        assertEquals(Optional.of(new TrailingState(bd("108"), true)), restarted.state("AAPL"));
    }

    @Test
    void aGuardWithoutTrailingColumnsIsRestoredWithItsStopOnly() {
        guardStore.upsert("MSFT", bd("90"), bd("120"));

        ExitGuard restarted = new ExitGuard();
        new GuardLoader(restarted, guardStore, new TrailingStateWriter(restarted, guardStore)).run(null);

        assertTrue(restarted.state("MSFT").isEmpty());
        assertTrue(restarted.onPrice("MSFT", bd("125")).isEmpty(), "no fixed take-profit after a restart");
        assertEquals(ExitType.EXIT_STOP, restarted.onPrice("MSFT", bd("89")).orElseThrow().type());
    }

    @Test
    void anInconsistentStoredStateArmsTheStopOnly() {
        guardStore.rows.put("NVDA", new StoredGuard("NVDA", bd("90"), null,
                new TrailingLevels(bd("110"), bd("2"), bd("0.2")), null, true, null));

        ExitGuard restarted = new ExitGuard();
        new GuardLoader(restarted, guardStore, new TrailingStateWriter(restarted, guardStore)).run(null);

        assertTrue(restarted.state("NVDA").isEmpty());
        assertEquals(ExitType.EXIT_STOP, restarted.onPrice("NVDA", bd("89")).orElseThrow().type());
    }

    /** The persisted guards in memory, with the semantics of {@link PositionGuardStore}'s SQL. */
    private static final class InMemoryGuardStore extends PositionGuardStore {
        final Map<String, StoredGuard> rows = new LinkedHashMap<>();
        final List<TrailingState> stateWrites = new ArrayList<>();

        InMemoryGuardStore() {
            super(null);
        }

        @Override
        public void upsert(String secCode, BigDecimal stop, BigDecimal takeProfit) {
            StoredGuard old = rows.get(secCode);
            rows.put(secCode, old == null
                    ? new StoredGuard(secCode, stop, takeProfit, null, null, false, null)
                    : new StoredGuard(secCode, stop, takeProfit, old.trailing(), old.best(), old.activated(),
                            old.riskKzt()));
        }

        @Override
        public void saveTrailing(String secCode, TrailingLevels trailing, BigDecimal riskKzt) {
            StoredGuard old = rows.get(secCode);
            rows.put(secCode, new StoredGuard(secCode, old.stop(), old.takeProfit(), trailing, null, false, riskKzt));
        }

        @Override
        public void saveTrailingState(String secCode, TrailingState state) {
            stateWrites.add(state);
            StoredGuard old = rows.get(secCode);
            rows.put(secCode, new StoredGuard(secCode, old.stop(), old.takeProfit(), old.trailing(), state.best(),
                    state.activated(), old.riskKzt()));
        }

        @Override
        public void delete(String secCode) {
            rows.remove(secCode);
        }

        @Override
        public List<StoredGuard> findAllGuards() {
            return List.copyOf(rows.values());
        }
    }
}
