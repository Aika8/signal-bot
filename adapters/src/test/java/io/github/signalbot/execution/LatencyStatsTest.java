package io.github.signalbot.execution;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LatencyStatsTest {

    @Test
    void emptyIsZero() {
        assertEquals(0L, LatencyStats.median(List.of()));
        assertEquals(0L, LatencyStats.max(List.of()));
    }

    @Test
    void oddMedianAndMax() {
        List<Long> v = List.of(300L, 100L, 200L);
        assertEquals(200L, LatencyStats.median(v));
        assertEquals(300L, LatencyStats.max(v));
    }

    @Test
    void evenMedianAveragesMiddleTwo() {
        assertEquals(250L, LatencyStats.median(List.of(100L, 200L, 300L, 400L)));
    }
}
