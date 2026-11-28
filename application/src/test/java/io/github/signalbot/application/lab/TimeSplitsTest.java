package io.github.signalbot.application.lab;

import io.github.signalbot.application.lab.TimeSplits.Period;
import io.github.signalbot.application.lab.TimeSplits.Split;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeSplitsTest {

    private static final LocalDate FROM = LocalDate.of(2018, 1, 1);
    private static final LocalDate TO = LocalDate.of(2025, 12, 31);

    private static List<Split> windows() {
        return TimeSplits.walkForward(FROM, TO, 250, 60, 60);
    }

    private static long days(Period p) {
        return ChronoUnit.DAYS.between(p.start(), p.end()) + 1;
    }

    @Test
    void windowsHaveTheAskedLengthsAndStep() {
        List<Split> ws = windows();
        assertEquals(44, ws.size()); // 2 922 days: 43 · 60 + 310 ≤ 2 922 < 44 · 60 + 310
        for (int i = 0; i < ws.size(); i++) {
            Split w = ws.get(i);
            assertEquals(250, days(w.train()), "train of window " + i);
            assertEquals(60, days(w.test()), "test of window " + i);
            assertEquals(FROM.plusDays(60L * i), w.train().start(), "start of window " + i);
        }
        assertEquals(FROM, ws.get(0).train().start());
        assertEquals(LocalDate.of(2018, 9, 7), ws.get(0).train().end());
    }

    @Test
    void testPartsNeverOverlap() {
        List<Split> ws = windows();
        for (int i = 1; i < ws.size(); i++) {
            assertTrue(ws.get(i).test().start().isAfter(ws.get(i - 1).test().end()), "window " + i);
        }
        for (int i = 0; i < ws.size(); i++) {
            for (int j = i + 1; j < ws.size(); j++) {
                Period a = ws.get(i).test();
                Period b = ws.get(j).test();
                assertFalse(a.contains(b.start()) || a.contains(b.end()) || b.contains(a.start()),
                        "windows " + i + " and " + j);
            }
        }
    }

    @Test
    void everyTestPartStartsTheDayAfterItsTrainPartEnds() {
        for (Split w : windows()) {
            assertTrue(w.test().start().isAfter(w.train().end()));
            assertEquals(w.train().end().plusDays(1), w.test().start());
        }
    }

    @Test
    void lastPartialWindowIsDropped() {
        List<Split> ws = windows();
        Split last = ws.get(ws.size() - 1);
        assertEquals(LocalDate.of(2025, 10, 1), last.test().start());
        assertEquals(LocalDate.of(2025, 11, 29), last.test().end());
        // the next window would test 2025-11-30 … 2026-01-28, past the end
        assertTrue(last.test().end().plusDays(60).isAfter(TO));
        for (Split w : ws) {
            assertFalse(w.test().end().isAfter(TO));
        }
    }

    @Test
    void windowEndingExactlyOnTheLastDayIsKept() {
        // 10 days: train 4 + test 3 from day 0 ends on day 6; step 3 → second window tests days 7…9 = the end
        LocalDate start = LocalDate.of(2020, 1, 1);
        List<Split> ws = TimeSplits.walkForward(start, start.plusDays(9), 4, 3, 3);
        assertEquals(2, ws.size());
        assertEquals(start.plusDays(9), ws.get(1).test().end());
    }

    @Test
    void periodShorterThanOneWindowGivesNoWindows() {
        assertEquals(List.of(), TimeSplits.walkForward(FROM, FROM.plusDays(308), 250, 60, 60));
    }

    @Test
    void holdoutPutsTheDateItselfInTheHoldout() {
        LocalDate cut = LocalDate.of(2022, 1, 1);
        Split s = TimeSplits.holdout(FROM, TO, cut);
        assertEquals(new Period(FROM, LocalDate.of(2021, 12, 31)), s.train());
        assertEquals(new Period(cut, TO), s.test());
        assertTrue(s.test().contains(cut));
        assertFalse(s.train().contains(cut));
    }

    @Test
    void holdoutOnTheLastDayLeavesOneHoldoutDay() {
        Split s = TimeSplits.holdout(FROM, TO, TO);
        assertEquals(new Period(TO, TO), s.test());
        assertEquals(TO.minusDays(1), s.train().end());
    }

    @Test
    void holdoutOutsideThePeriodOrWithEmptyTrainIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> TimeSplits.holdout(FROM, TO, FROM));
        assertThrows(IllegalArgumentException.class, () -> TimeSplits.holdout(FROM, TO, FROM.minusDays(1)));
        assertThrows(IllegalArgumentException.class, () -> TimeSplits.holdout(FROM, TO, TO.plusDays(1)));
        assertThrows(NullPointerException.class, () -> TimeSplits.holdout(FROM, TO, null));
    }

    @Test
    void badWalkForwardArgumentsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> TimeSplits.walkForward(TO, FROM, 250, 60, 60));
        assertThrows(IllegalArgumentException.class, () -> TimeSplits.walkForward(FROM, TO, 0, 60, 60));
        assertThrows(IllegalArgumentException.class, () -> TimeSplits.walkForward(FROM, TO, 250, 0, 60));
        assertThrows(IllegalArgumentException.class, () -> TimeSplits.walkForward(FROM, TO, 250, 60, 0));
        // a step shorter than the test part would make test parts overlap
        assertThrows(IllegalArgumentException.class, () -> TimeSplits.walkForward(FROM, TO, 250, 60, 59));
    }

    @Test
    void periodEndingBeforeItStartsIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new Period(TO, FROM));
    }
}
