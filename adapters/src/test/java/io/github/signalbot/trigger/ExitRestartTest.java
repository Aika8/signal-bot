package io.github.signalbot.trigger;

import io.github.signalbot.application.trigger.ExitGuard;
import io.github.signalbot.application.trigger.ExitSignal;
import io.github.signalbot.application.trigger.ExitType;
import io.github.signalbot.application.trigger.TrailingLevels;
import io.github.signalbot.application.trigger.TrailingSnapshot;
import io.github.signalbot.application.trigger.TrailingState;
import io.github.signalbot.application.trigger.TriggerEvaluator;
import io.github.signalbot.application.trigger.TriggerSettings;
import io.github.signalbot.config.ExecutionProperties;
import io.github.signalbot.domain.model.Action;
import io.github.signalbot.domain.model.Ccy;
import io.github.signalbot.domain.model.Portfolio;
import io.github.signalbot.domain.model.Position;
import io.github.signalbot.domain.model.Venue;
import io.github.signalbot.domain.port.Notifier;
import io.github.signalbot.domain.port.PaperWallet;
import io.github.signalbot.domain.port.PortfolioReader;
import io.github.signalbot.execution.ExecutionGateway;
import io.github.signalbot.execution.ExecutionMode;
import io.github.signalbot.execution.ExecutionService;
import io.github.signalbot.execution.ExecutionSource;
import io.github.signalbot.persistence.ExecutionStore;
import io.github.signalbot.persistence.PositionGuardStore;
import io.github.signalbot.persistence.PositionGuardStore.StoredGuard;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SB-034 (#31): once an exit has fired and {@link ExitGuardHandler#handle} ran, a restart (a new {@link ExitGuard}
 * and {@link GuardLoader} over the same storage) arms nothing for that symbol, so the exit does not fire again while
 * its SELL waits for confirmation.
 */
class ExitRestartTest {

    private final InMemoryGuardStore guardStore = new InMemoryGuardStore();
    private final ExecutionStore executionStore = mock(ExecutionStore.class);
    private final ExecutionGateway gateway = mock(ExecutionGateway.class);
    private final PortfolioReader portfolioReader = mock(PortfolioReader.class);
    private final ExitGuard exitGuard = new ExitGuard();
    private final TrailingStateWriter writer = new TrailingStateWriter(exitGuard, guardStore);

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    @SuppressWarnings("unchecked")
    private ExitGuardHandler handler() {
        ObjectProvider<ExecutionGateway> gatewayProvider = mock(ObjectProvider.class);
        when(gatewayProvider.getIfAvailable()).thenReturn(gateway);
        ObjectProvider<Notifier> notifier = mock(ObjectProvider.class);
        ExecutionService service = new ExecutionService(executionStore, mock(PaperWallet.class), gatewayProvider,
                new ExecutionProperties(ExecutionMode.MANUAL, Duration.ofMinutes(30), Duration.ofSeconds(60), 15000),
                exitGuard, guardStore,
                new TriggerEvaluator(new TriggerSettings(bd("0.03"), Duration.ofMinutes(15))),
                mock(ContextAssembler.class), TransactionOperations.withoutTransaction());
        return new ExitGuardHandler(portfolioReader, notifier, exitGuard, guardStore, service);
    }

    private void holds(String symbol, String quantity) {
        Position p = new Position(symbol, null, Venue.US, bd(quantity), bd(quantity), bd("100"), bd("110"),
                Ccy.USD, bd("0"), bd("0"), bd("0"), false, false, Instant.now());
        when(portfolioReader.read()).thenReturn(new Portfolio(List.of(p), bd("0"), Map.of(), Instant.now()));
    }

    /** One price as the live paths deliver it: guard, handler on a signal, then the trailing writer. */
    private Optional<ExitSignal> tick(ExitGuard guard, ExitGuardHandler h, TrailingStateWriter w, String price) {
        Optional<ExitSignal> signal = guard.onPrice("AAPL", bd(price));
        signal.ifPresent(h::handle);
        w.afterPrice("AAPL");
        return signal;
    }

    private ExitGuard restart() {
        ExitGuard restarted = new ExitGuard();
        new GuardLoader(restarted, guardStore, new TrailingStateWriter(restarted, guardStore)).run(null);
        return restarted;
    }

    @Test
    void aFiredStopIsNotReArmedAfterARestart() {
        holds("AAPL", "5");
        when(executionStore.insertPending(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(7L);
        // As a BUY fill without an ATR leaves it: a stored and armed stop, no trailing.
        guardStore.upsert("AAPL", bd("96"), bd("130"));
        exitGuard.arm("AAPL", bd("96"), null);
        ExitGuardHandler handler = handler();

        ExitSignal fired = tick(exitGuard, handler, writer, "95").orElseThrow();
        assertEquals(ExitType.EXIT_STOP, fired.type());
        verify(gateway).promptExecution(eq(7L), eq("AAPL"), eq(Action.SELL), any(), any());
        assertFalse(guardStore.rows.containsKey("AAPL"), "the fired guard is gone from storage");

        ExitGuard restarted = restart();

        assertFalse(restarted.armedSymbols().contains("AAPL"));
        assertTrue(restarted.onPrice("AAPL", bd("95")).isEmpty());
        assertTrue(restarted.onPrice("AAPL", bd("50")).isEmpty());
    }

    @Test
    void aFiredTrailingExitIsNotReArmedAfterARestart() {
        holds("AAPL", "5");
        when(executionStore.insertPending(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(8L);
        // As a BUY fill with an ATR leaves it: stop 96, trailing activates at 108, offset 2, spread 0.2.
        TrailingLevels levels = new TrailingLevels(bd("108"), bd("2"), bd("0.2"));
        guardStore.upsert("AAPL", bd("96"), bd("130"));
        guardStore.saveTrailing("AAPL", levels, bd("10000"));
        exitGuard.armTrailing("AAPL", bd("96"), levels);
        ExitGuardHandler handler = handler();

        assertTrue(tick(exitGuard, handler, writer, "108").isEmpty());
        assertTrue(tick(exitGuard, handler, writer, "115").isEmpty());
        ExitSignal fired = tick(exitGuard, handler, writer, "112.9").orElseThrow();
        assertEquals(ExitType.EXIT_TRAIL, fired.type());
        verify(gateway).promptExecution(eq(8L), eq("AAPL"), eq(Action.SELL), any(), any());
        assertFalse(guardStore.rows.containsKey("AAPL"), "the fired guard is gone from storage");

        ExitGuard restarted = restart();

        assertFalse(restarted.armedSymbols().contains("AAPL"));
        assertTrue(restarted.state("AAPL").isEmpty());
        assertTrue(restarted.onPrice("AAPL", bd("112")).isEmpty(), "no trailing exit again");
        assertTrue(restarted.onPrice("AAPL", bd("95")).isEmpty(), "no stop below 96 either");
    }

    /** The persisted guards in memory, with the semantics of {@link PositionGuardStore}'s SQL. */
    private static final class InMemoryGuardStore extends PositionGuardStore {
        final Map<String, StoredGuard> rows = new LinkedHashMap<>();

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
            if (old != null) {
                rows.put(secCode, new StoredGuard(secCode, old.stop(), old.takeProfit(), trailing, null, false,
                        riskKzt));
            }
        }

        @Override
        public void saveTrailingState(String secCode, TrailingState state) {
            StoredGuard old = rows.get(secCode);
            if (old != null) {
                rows.put(secCode, new StoredGuard(secCode, old.stop(), old.takeProfit(), old.trailing(),
                        state.best(), state.activated(), old.riskKzt()));
            }
        }

        @Override
        public boolean saveTrailingState(String secCode, TrailingSnapshot snapshot) {
            StoredGuard old = rows.get(secCode);
            if (old == null || old.trailing() == null
                    || old.trailing().activation().compareTo(snapshot.levels().activation()) != 0
                    || old.trailing().offset().compareTo(snapshot.levels().offset()) != 0) {
                return false;
            }
            rows.put(secCode, new StoredGuard(secCode, old.stop(), old.takeProfit(), old.trailing(),
                    snapshot.best(), snapshot.activated(), old.riskKzt()));
            return true;
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
