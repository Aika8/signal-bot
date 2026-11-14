package io.github.signalbot.execution;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically auto-fills (AUTO mode) or expires overdue pending executions. */
@Component
public class ExecutionSweeper {

    private final ExecutionService executionService;

    public ExecutionSweeper(ExecutionService executionService) {
        this.executionService = executionService;
    }

    @Scheduled(fixedDelayString = "${signalbot.execution.sweep-ms:15000}")
    public void sweep() {
        executionService.sweep();
    }
}
