package io.github.signalbot.trigger;

import io.github.signalbot.application.trigger.ExitGuard;
import io.github.signalbot.application.trigger.ExitSignal;
import io.github.signalbot.application.trigger.TrailingLevels;
import io.github.signalbot.application.trigger.TriggerEvaluator;
import io.github.signalbot.config.WatchlistProperties;
import io.github.signalbot.domain.model.PriceTick;
import io.github.signalbot.domain.port.MarketDataFeed;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.DefaultApplicationArguments;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** AUTO-20261127-3: a failing exit step must not skip the rest of the tick nor escape into the feed. */
class PriceStreamTickFailureTest {

    private final ExitGuard exitGuard = new ExitGuard();
    private final ExitGuardHandler exitHandler = mock(ExitGuardHandler.class);
    private final TrailingStateWriter trailingWriter = mock(TrailingStateWriter.class);
    private final TriggerEvaluator evaluator = mock(TriggerEvaluator.class);
    private final SignalDispatcher dispatcher = mock(SignalDispatcher.class);
    private final MarketDataFeed feed = mock(MarketDataFeed.class);

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    @SuppressWarnings("unchecked")
    private Consumer<PriceTick> subscribe() {
        Consumer<PriceTick>[] captured = new Consumer[1];
        doAnswer(inv -> {
            captured[0] = inv.getArgument(1);
            return (AutoCloseable) () -> { };
        }).when(feed).subscribe(anyCollection(), any());
        ObjectProvider<MarketDataFeed> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(feed);
        new PriceStreamRunner(provider, new WatchlistProperties(List.of("AAPL", "MSFT")), evaluator, dispatcher,
                exitGuard, exitHandler, trailingWriter).run(new DefaultApplicationArguments());
        assertNotNull(captured[0], "runner subscribed");
        return captured[0];
    }

    @Test
    void aFailingExitStillRunsTrailingAndEvaluatorAndDoesNotEscape() {
        exitGuard.armTrailing("AAPL", bd("90"), new TrailingLevels(bd("100"), bd("5"), bd("0.2")));
        doThrow(new IllegalStateException("db down")).when(exitHandler).handle(any(ExitSignal.class));
        when(evaluator.onPriceTick(any(), any(), any())).thenReturn(Optional.empty());
        Consumer<PriceTick> onTick = subscribe();
        Instant now = Instant.parse("2026-11-27T15:00:00Z");

        assertDoesNotThrow(() -> onTick.accept(new PriceTick("AAPL", bd("89"), now)));

        verify(exitHandler).handle(any(ExitSignal.class));
        verify(trailingWriter).afterPrice("AAPL");
        verify(evaluator).onPriceTick(eq("AAPL"), eq(bd("89")), eq(now));
    }

    @Test
    void theNextTickOfTheSameMessageIsStillProcessed() {
        exitGuard.armTrailing("AAPL", bd("90"), new TrailingLevels(bd("100"), bd("5"), bd("0.2")));
        doThrow(new IllegalStateException("db down")).when(exitHandler).handle(any(ExitSignal.class));
        when(evaluator.onPriceTick(any(), any(), any())).thenReturn(Optional.empty());
        Consumer<PriceTick> onTick = subscribe();
        Instant now = Instant.parse("2026-11-27T15:00:00Z");

        // The feed adapter delivers one message's ticks with forEach(onTick): an escaping exception would stop it.
        assertDoesNotThrow(() -> List.of(new PriceTick("AAPL", bd("89"), now), new PriceTick("MSFT", bd("400"), now))
                .forEach(onTick));

        verify(trailingWriter).afterPrice("MSFT");
        verify(evaluator).onPriceTick(eq("MSFT"), eq(bd("400")), eq(now));
    }
}
