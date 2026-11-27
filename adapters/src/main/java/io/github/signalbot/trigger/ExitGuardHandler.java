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
 * loss block entirely. The SELL still goes through the PENDING confirmation flow. A trailing exit (D-004)
 * carries its suggested limit (breakout − spread) into the execution.
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
        try {
            guardStore.delete(symbol);
        } catch (RuntimeException e) {
            // AUTO-20261127-5: a store outage must not silence a fired exit. The in-memory guard is disarmed;
            // the stored row may re-arm on restart, but the alert and the SELL still go out now.
            LOG.error("Exit {} for {}: deleting the stored guard failed; alerting anyway",
                    signal.type(), symbol, e);
        }

        Position held;
        try {
            held = findPosition(symbol);
        } catch (RuntimeException e) {
            // D-001: a failed source throws. The guard is already gone, so the alert is the only trace left:
            // send it without a quantity and submit no SELL (nothing to size it with).
            LOG.error("Exit {} for {}: portfolio read failed; alerting without a position check",
                    signal.type(), symbol, e);
            push(symbol, signal, " (position not checked)");
            return;
        }
        if (held == null) {
            LOG.info("Exit {} for {} but no position held; disarmed only", signal.type(), symbol);
            return;
        }
        push(symbol, signal, "");
        Verdict sell = new Verdict(symbol, Action.SELL, held.quantity(), signal.triggerPrice(),
                null, null, 1.0, signal.type() + " hit at " + signal.triggerPrice(),
                Instant.now().plus(Duration.ofMinutes(15)));
        if (signal.suggestedLimit() == null) {
            executionService.submit(sell, ExecutionSource.EXIT, signal.triggerPrice());
        } else {
            // EXIT_TRAIL (D-004): ref = breakout price, suggested limit = breakout − spread, stored with the row.
            executionService.submit(sell, ExecutionSource.EXIT, signal.triggerPrice(), null,
                    signal.suggestedLimit());
        }
    }

    private Position findPosition(String symbol) {
        Portfolio portfolio = portfolioReader.read();
        return portfolio.positions().stream()
                .filter(p -> symbol.equals(p.secCode()))
                .findFirst()
                .orElse(null);
    }

    private void push(String symbol, ExitSignal signal, String note) {
        String message = "SELL " + symbol + " now — " + signal.type() + " hit at " + signal.triggerPrice()
                + (signal.suggestedLimit() == null ? "" : ", limit " + signal.suggestedLimit()) + note;
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
