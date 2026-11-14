package io.github.signalbot.trigger;

import io.github.signalbot.application.trigger.ExitGuard;
import io.github.signalbot.domain.model.PriceSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * REST safety net for the exit guard: polls a snapshot for each armed symbol and
 * runs the guard. Covers websocket gaps (when ticks stop, the snapshot still
 * catches a breached stop/take-profit).
 */
@Component
public class SnapshotGuardPoller {

    private static final Logger LOG = LoggerFactory.getLogger(SnapshotGuardPoller.class);

    private final ExitGuard exitGuard;
    private final ContextAssembler assembler;
    private final ExitGuardHandler exitHandler;

    public SnapshotGuardPoller(ExitGuard exitGuard, ContextAssembler assembler, ExitGuardHandler exitHandler) {
        this.exitGuard = exitGuard;
        this.assembler = assembler;
        this.exitHandler = exitHandler;
    }

    @Scheduled(fixedDelayString = "${signalbot.trigger.guard-snapshot-ms:30000}")
    public void poll() {
        for (String symbol : exitGuard.armedSymbols()) {
            try {
                PriceSnapshot snapshot = assembler.snapshot(symbol);
                if (snapshot != null && snapshot.last() != null) {
                    exitGuard.onPrice(symbol, snapshot.last()).ifPresent(exitHandler::handle);
                }
            } catch (Exception e) {
                LOG.warn("Snapshot guard check failed for {}: {}", symbol, e.getMessage());
            }
        }
    }
}
