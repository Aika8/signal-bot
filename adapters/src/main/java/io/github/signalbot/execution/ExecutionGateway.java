package io.github.signalbot.execution;

import io.github.signalbot.domain.model.Action;

import java.math.BigDecimal;

/** Outbound channel that asks the operator to confirm an execution (Telegram buttons). */
public interface ExecutionGateway {

    void promptExecution(long executionId, String symbol, Action action, BigDecimal quantity, BigDecimal refPrice);
}
