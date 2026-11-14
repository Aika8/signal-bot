package io.github.signalbot.application.trigger;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebouncerTest {

    private static final Instant T0 = Instant.parse("2026-10-23T15:00:00Z");

    @Test
    void allowsFirstThenBlocksWithinWindow() {
        Debouncer debouncer = new Debouncer(Duration.ofMinutes(10));
        assertTrue(debouncer.tryDispatch("AAPL", T0));
        assertFalse(debouncer.tryDispatch("AAPL", T0.plus(Duration.ofMinutes(9))));
    }

    @Test
    void allowsAgainAfterWindow() {
        Debouncer debouncer = new Debouncer(Duration.ofMinutes(10));
        assertTrue(debouncer.tryDispatch("AAPL", T0));
        assertTrue(debouncer.tryDispatch("AAPL", T0.plus(Duration.ofMinutes(10))));
    }

    @Test
    void differentSymbolsAreIndependent() {
        Debouncer debouncer = new Debouncer(Duration.ofMinutes(10));
        assertTrue(debouncer.tryDispatch("AAPL", T0));
        assertTrue(debouncer.tryDispatch("MSFT", T0));
    }
}
