package io.github.signalbot.execution;

import java.util.List;

/** Pure median/max over latency samples (ms). */
public final class LatencyStats {

    private LatencyStats() {
    }

    public static long max(List<Long> values) {
        return values.stream().mapToLong(Long::longValue).max().orElse(0L);
    }

    public static long median(List<Long> values) {
        if (values.isEmpty()) {
            return 0L;
        }
        List<Long> sorted = values.stream().sorted().toList();
        int n = sorted.size();
        if (n % 2 == 1) {
            return sorted.get(n / 2);
        }
        return (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }
}
