package io.github.signalbot.trigger;

import io.github.signalbot.application.trigger.ExitGuard;
import io.github.signalbot.application.trigger.ExitSignal;
import io.github.signalbot.application.trigger.ExitType;
import io.github.signalbot.application.trigger.TrailingLevels;
import io.github.signalbot.application.trigger.TriggerEvaluator;
import io.github.signalbot.application.trigger.TriggerSettings;
import io.github.signalbot.config.ExecutionProperties;
import io.github.signalbot.config.TelegramProperties;
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
import io.github.signalbot.execution.ExecutionState;
import io.github.signalbot.persistence.ExecutionStore;
import io.github.signalbot.persistence.ExecutionStore.ExecutionRow;
import io.github.signalbot.persistence.PositionGuardStore;
import io.github.signalbot.telegram.ExecutionPromptFormatter;
import io.github.signalbot.telegram.SignalBotTelegramBot;
import io.github.signalbot.telegram.TelegramExecutionGateway;
import io.github.signalbot.telegram.TelegramSender;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SB-014 / D-004: a trailing exit becomes a SELL execution (source EXIT) with ref price = breakout price and the
 * suggested limit = breakout − spread, stored with the row before the prompt goes out, and shown in the prompt.
 */
class TrailingExitHandlerTest {

    private final ExecutionStore store = mock(ExecutionStore.class);
    private final ExecutionGateway gateway = mock(ExecutionGateway.class);
    private final PositionGuardStore guardStore = mock(PositionGuardStore.class);
    private final PortfolioReader portfolioReader = mock(PortfolioReader.class);
    private final ExitGuard exitGuard = new ExitGuard();

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    @SuppressWarnings("unchecked")
    private ExitGuardHandler handler() {
        ObjectProvider<ExecutionGateway> gatewayProvider = mock(ObjectProvider.class);
        when(gatewayProvider.getIfAvailable()).thenReturn(gateway);
        ObjectProvider<Notifier> notifier = mock(ObjectProvider.class);
        ExecutionService service = new ExecutionService(store, mock(PaperWallet.class), gatewayProvider,
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

    /** Trailing armed with spread 0.2; activates at 100, best 116, offset 5 → fires at 110 (below 111). */
    private ExitSignal trailingExitAt110() {
        exitGuard.armTrailing("AAPL", bd("90"), new TrailingLevels(bd("100"), bd("5"), bd("0.2")));
        assertTrue(exitGuard.onPrice("AAPL", bd("100")).isEmpty());
        assertTrue(exitGuard.onPrice("AAPL", bd("116")).isEmpty());
        ExitSignal signal = exitGuard.onPrice("AAPL", bd("110")).orElseThrow();
        assertEquals(ExitType.EXIT_TRAIL, signal.type());
        return signal;
    }

    @Test
    void trailingExitCreatesASellWithRefPriceAndSuggestedLimitStoredBeforeThePrompt() {
        holds("AAPL", "7");
        ExitSignal signal = trailingExitAt110();
        when(store.insertPending(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(21L);

        handler().handle(signal);

        ArgumentCaptor<BigDecimal> limit = ArgumentCaptor.forClass(BigDecimal.class);
        InOrder order = inOrder(store, gateway);
        order.verify(store).insertPending(eq("AAPL"), eq(Action.SELL), eq(ExecutionSource.EXIT), eq(bd("7")),
                isNull(), isNull(), eq(bd("110")), any(Instant.class), isNull(), limit.capture());
        order.verify(gateway).promptExecution(eq(21L), eq("AAPL"), eq(Action.SELL), eq(bd("7")), eq(bd("110")));
        assertEquals(0, bd("109.80").compareTo(limit.getValue()), "limit " + limit.getValue());
        verify(store, never()).insertPending(any(), any(), any(), any(), any(), any(), any(), any());
        verify(guardStore).delete("AAPL");
        assertTrue(exitGuard.armedSymbols().isEmpty());
    }

    @Test
    void stopExitStaysWithoutALimit() {
        holds("AAPL", "7");
        exitGuard.armTrailing("AAPL", bd("90"), new TrailingLevels(bd("100"), bd("5"), bd("0.2")));
        ExitSignal stop = exitGuard.onPrice("AAPL", bd("89")).orElseThrow();
        assertEquals(ExitType.EXIT_STOP, stop.type());
        assertNull(stop.suggestedLimit());
        when(store.insertPending(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(22L);

        handler().handle(stop);

        verify(store).insertPending(eq("AAPL"), eq(Action.SELL), eq(ExecutionSource.EXIT), eq(bd("7")),
                isNull(), isNull(), eq(bd("89")), any(Instant.class));
        verify(store, never()).insertPending(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(gateway).promptExecution(eq(22L), eq("AAPL"), eq(Action.SELL), eq(bd("7")), eq(bd("89")));
    }

    @Test
    void noPositionHeldSubmitsNothing() {
        when(portfolioReader.read()).thenReturn(new Portfolio(List.of(), bd("0"), Map.of(), Instant.now()));
        ExitSignal signal = trailingExitAt110();

        handler().handle(signal);

        verify(store, never()).insertPending(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(gateway, never()).promptExecution(any(Long.class), any(), any(), any(), any());
    }

    @Test
    void promptShowsTheStoredSuggestedLimit() throws Exception {
        SignalBotTelegramBot bot = mock(SignalBotTelegramBot.class);
        TelegramSender sender = mock(TelegramSender.class);
        when(store.find(21L)).thenReturn(Optional.of(new ExecutionRow(21L, "AAPL", Action.SELL,
                ExecutionSource.EXIT, bd("7"), null, null, bd("110"), ExecutionState.PENDING, Instant.now(),
                null, bd("109.8"))));

        new TelegramExecutionGateway(bot, sender, new TelegramProperties(true, "token", "bot", "42"), store)
                .promptExecution(21L, "AAPL", Action.SELL, bd("7"), bd("110"));

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(sender).sendMarkup(eq(bot), eq("42"), text.capture(), any());
        assertEquals("SELL AAPL x7 (ref 110) — confirm?\nSuggested limit 109.80", text.getValue());
        assertTrue(text.getValue().contains("limit 109.80"));
    }

    @Test
    void promptWithoutALimitIsUnchanged() {
        assertEquals("SELL AAPL x7 (ref 89) — confirm?",
                ExecutionPromptFormatter.format("AAPL", Action.SELL, bd("7"), bd("89"), null, null, null));
    }
}
