package io.github.signalbot.trigger;

import io.github.signalbot.application.trigger.ExitGuard;
import io.github.signalbot.persistence.PositionGuardStore;
import io.github.signalbot.persistence.PositionGuardStore.GuardRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Re-arms the exit guard from the persisted levels on startup. */
@Component
@Order(0)
public class GuardLoader implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(GuardLoader.class);

    private final ExitGuard exitGuard;
    private final PositionGuardStore guardStore;

    public GuardLoader(ExitGuard exitGuard, PositionGuardStore guardStore) {
        this.exitGuard = exitGuard;
        this.guardStore = guardStore;
    }

    @Override
    public void run(ApplicationArguments args) {
        int count = 0;
        for (GuardRow row : guardStore.findAll()) {
            exitGuard.arm(row.secCode(), row.stop(), row.takeProfit());
            count++;
        }
        LOG.info("Re-armed {} position guard(s) from storage.", count);
    }
}
