package io.github.signalbot.trigger;

import io.github.signalbot.application.trigger.ExitGuard;
import io.github.signalbot.persistence.PositionGuardStore;
import io.github.signalbot.application.trigger.TrailingState;
import io.github.signalbot.persistence.PositionGuardStore.StoredGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Re-arms the exit guard from the persisted levels on startup. A guard with trailing levels is restored with its
 * best price and activation (D-004); one without is armed with its stop only — a fixed take-profit is never re-armed
 * after a restart (D-004).
 */
@Component
@Order(0)
public class GuardLoader implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(GuardLoader.class);

    private final ExitGuard exitGuard;
    private final PositionGuardStore guardStore;
    private final TrailingStateWriter trailingWriter;

    public GuardLoader(ExitGuard exitGuard, PositionGuardStore guardStore, TrailingStateWriter trailingWriter) {
        this.exitGuard = exitGuard;
        this.guardStore = guardStore;
        this.trailingWriter = trailingWriter;
    }

    @Override
    public void run(ApplicationArguments args) {
        int count = 0;
        for (StoredGuard row : guardStore.findAllGuards()) {
            load(row);
            count++;
        }
        LOG.info("Re-armed {} position guard(s) from storage.", count);
    }

    private void load(StoredGuard row) {
        if (row.trailing() == null) {
            if (row.takeProfit() != null) {
                LOG.info("Guard {}: no trailing levels; stop only, take-profit {} not re-armed (D-004)",
                        row.secCode(), row.takeProfit());
            }
            exitGuard.arm(row.secCode(), row.stop(), null);
            return;
        }
        TrailingState state;
        try {
            state = new TrailingState(row.best(), row.activated());
        } catch (IllegalArgumentException e) {
            LOG.error("Guard {}: inconsistent trailing state (best {}, activated {}): {}; arming the stop only",
                    row.secCode(), row.best(), row.activated(), e.getMessage());
            exitGuard.arm(row.secCode(), row.stop(), null);
            return;
        }
        exitGuard.restore(row.secCode(), row.stop(), row.trailing(), state.best(), state.activated());
        trailingWriter.remember(row.secCode(), state);
    }
}
