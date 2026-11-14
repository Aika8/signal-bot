package io.github.signalbot.trigger;

import io.github.signalbot.application.trigger.ExitGuard;
import io.github.signalbot.application.trigger.ExitSignal;
import io.github.signalbot.domain.model.Action;
import io.github.signalbot.domain.model.Portfolio;
import io.github.signalbot.domain.model.Position;
import io.github.signalbot.domain.model.Verdict;
import io.github.signalbot.domain.port.Notifier;
import io.github.signalbot.domain.port.PortfolioReader;
import io.github.signalbot.execution.ExecutionService;
import io.github.signalbot.execution.ExecutionSource;
import io.github.signalbot.persistence.PositionGuardStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Acts on a protective exit: pushes an immediate Telegram alert and submits a
 * SELL execution. Bypasses Claude, debounce, the 24h holding rule and the daily
 * loss block entirely. The SELL still goes through the PENDING confirmation flow.
 */
@Component
public class ExitGuardHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ExitGuardHandler.class);

    private final PortfolioReader portfolioReader;
    private final ObjectProvider<Notifier> notifier;
    private final ExitGuard exitGuard;
    private final PositionGuardStore guardStore;
    private final ExecutionService executionService;

    public ExitGuardHandler(PortfolioReader portfolioReader, ObjectProvider<Notifier> notifier,
                            ExitGuard exitGuard, PositionGuardStore guardStore, ExecutionService executionService) {
        this.portfolioReader = portfolioReader;
        this.notifier = notifier;
        this.exitGuard = exitGuard;
        this.guardStore = guardStore;
        this.executionService = executionService;
    }

    public void handle(ExitSignal signal) {
        String symbol = signal.symbol();
        // Disarm so the level never re-fires until a new verdict re-arms it.
        exitGuard.disarm(symbol);
        guardStore.delete(symbol);

        Position held = findPosition(symbol);
        if (held == null) {
            LOG.info("Exit {} for {} but no position held; disarmed only", signal.type(), symbol);
            return;
        }
        push(symbol, signal);
        Verdict sell = new Verdict(symbol, Action.SELL, held.quantity(), signal.triggerPrice(),
                null, null, 1.0, signal.type() + " hit at " + signal.triggerPrice(),
                Instant.now().plus(Duration.ofMinutes(15)));
        executionService.submit(sell, ExecutionSource.EXIT, signal.triggerPrice());
    }

    private Position findPosition(String symbol) {
        Portfolio portfolio = portfolioReader.read();
        return portfolio.positions().stream()
                .filter(p -> symbol.equals(p.secCode()))
                .findFirst()
                .orElse(null);
    }

    private void push(String symbol, ExitSignal signal) {
        String message = "SELL " + symbol + " now — " + signal.type() + " hit at " + signal.triggerPrice();
        Notifier n = notifier.getIfAvailable();
        if (n != null) {
            Verdict alert = new Verdict(symbol, Action.SELL, java.math.BigDecimal.ZERO, signal.triggerPrice(),
                    null, null, 1.0, message, Instant.now().plus(Duration.ofMinutes(15)));
            n.send(alert, message);
        } else {
            LOG.info(message);
        }
    }
}
