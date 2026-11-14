package io.github.signalbot.application.trigger;

import java.time.Instant;

/** A fired trigger awaiting dispatch (debounce, then dry-run log or Claude call). */
public record TriggerSignal(String symbol, TriggerType type, String reason, Instant at) {
}
