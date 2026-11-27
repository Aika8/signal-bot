package io.github.signalbot.trigger;

import io.github.signalbot.application.trigger.ExitGuard;
import io.github.signalbot.application.trigger.ExitSignal;
import io.github.signalbot.application.trigger.ExitType;
import io.github.signalbot.application.trigger.TrailingLevels;
import io.github.signalbot.domain.model.Ccy;
import io.github.signalbot.domain.model.Portfolio;
import io.github.signalbot.domain.model.Position;
import io.github.signalbot.domain.model.Venue;
import io.github.signalbot.domain.model.Verdict;
import io.github.signalbot.domain.port.Notifier;
import io.github.signalbot.domain.port.PortfolioReader;
import io.github.signalbot.execution.ExecutionService;
import io.github.signalbot.persistence.PositionGuardStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AUTO-20261127-2: the guard is deleted before the portfolio is read (SB-034), so a failing read or a failing
 * SELL submit must not swallow the exit — the operator still gets the alert.
 */
class ExitHandlerFailureTest {

    private final PositionGuardStore guardStore = mock(PositionGuardStore.class);
    private final PortfolioReader portfolioReader = mock(PortfolioReader.class);
    private final ExecutionService executionService = mock(ExecutionService.class);
    private final Notifier notifier = mock(Notifier.class);
    private final ExitGuard exitGuard = new ExitGuard();

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    @SuppressWarnings("unchecked")
    private ExitGuardHandler handler() {
        ObjectProvider<Notifier> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(notifier);
        return new ExitGuardHandler(portfolioReader, provider, exitGuard, guardStore, executionService);
    }

    private ExitSignal stopAt89() {
        exitGuard.armTrailing("AAPL", bd("90"), new TrailingLevels(bd("100"), bd("5"), bd("0.2")));
        ExitSignal stop = exitGuard.onPrice("AAPL", bd("89")).orElseThrow();
        assertEquals(ExitType.EXIT_STOP, stop.type());
        return stop;
    }

    private String sentMessage() {
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(notifier).send(any(Verdict.class), text.capture());
        return text.getValue();
    }

    @Test
    void failedPortfolioReadStillAlertsAndSubmitsNoSell() {
        ExitSignal stop = stopAt89();
        when(portfolioReader.read()).thenThrow(new IllegalStateException("broker down"));

        handler().handle(stop);

        assertEquals("SELL AAPL now — EXIT_STOP hit at 89 (position not checked)", sentMessage());
        verifyNoInteractions(executionService);
        verify(guardStore).delete("AAPL");
        assertTrue(exitGuard.armedSymbols().isEmpty());
    }

    @Test
    void failedSellSubmitHasAlreadyAlerted() {
        ExitSignal stop = stopAt89();
        Position p = new Position("AAPL", null, Venue.US, bd("7"), bd("7"), bd("100"), bd("110"),
                Ccy.USD, bd("0"), bd("0"), bd("0"), false, false, Instant.now());
        when(portfolioReader.read()).thenReturn(new Portfolio(List.of(p), bd("0"), Map.of(), Instant.now()));
        doThrow(new IllegalStateException("db down")).when(executionService).submit(any(), any(), any());

        assertThrows(IllegalStateException.class, () -> handler().handle(stop));

        assertEquals("SELL AAPL now — EXIT_STOP hit at 89", sentMessage());
        verify(notifier).send(any(Verdict.class), anyString());
    }
}
