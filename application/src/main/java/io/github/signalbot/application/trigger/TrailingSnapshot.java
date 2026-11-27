package io.github.signalbot.application.trigger;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * The trailing levels of an armed guard together with the trailing state that belongs to them, read in one
 * synchronized call of {@link ExitGuard#snapshot}. The adapter writes the state only onto the stored guard with the
 * same levels, so a state read before a re-arm never lands on the new guard (issue #30).
 *
 * @param levels    the trailing levels the state was tracked against
 * @param best      the best price since activation; {@code null} before activation
 * @param activated whether the price has reached the activation level
 */
public record TrailingSnapshot(TrailingLevels levels, BigDecimal best, boolean activated) {

    public TrailingSnapshot {
        Objects.requireNonNull(levels, "levels");
        new TrailingState(best, activated); // same consistency rule as the persisted state
    }

    /** The mutable part alone, as {@link ExitGuard#state} reports it. */
    public TrailingState state() {
        return new TrailingState(best, activated);
    }
}
