package io.github.signalbot.application.risk;

/** Where the stop of a BUY came from (D-002). */
public enum StopSource {
    /** Claude's own stop, accepted because its distance is within the ATR band. */
    CLAUDE,
    /** {@code entry − K × ATR}, because Claude's stop was missing or outside the band. */
    ATR
}
