package io.github.signalbot.application.trigger;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Allows at most one dispatch per key within a window. Pure and thread-safe. */
public final class Debouncer {

    private final Duration window;
    private final Map<String, Instant> lastDispatch = new ConcurrentHashMap<>();

    public Debouncer(Duration window) {
        this.window = window;
    }

    /** Returns true and records the dispatch if the window has elapsed since the last one. */
    public synchronized boolean tryDispatch(String key, Instant now) {
        Instant last = lastDispatch.get(key);
        if (last != null && Duration.between(last, now).compareTo(window) < 0) {
            return false;
        }
        lastDispatch.put(key, now);
        return true;
    }
}
