package io.github.signalbot.telegram;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SB-039: long Telegram text is cut into parts of at most 4 096 chars, preferring a line break, losing nothing. */
class TelegramTextTest {

    @Test
    void tenThousandCharsWithoutLineBreaksBecomeThreeHardCutParts() {
        String text = "x".repeat(10_000);

        List<String> parts = TelegramText.split(text);

        assertEquals(3, parts.size());
        assertEquals(List.of(4096, 4096, 1808), parts.stream().map(String::length).toList());
        assertEquals(text, String.join("", parts));
    }

    @Test
    void tenThousandCharsOfLinesAreCutAtLineBreaks() {
        StringBuilder sb = new StringBuilder();
        int line = 0;
        while (sb.length() < 10_000) {
            sb.append("line ").append(line++).append(' ').append("y".repeat(60)).append('\n');
        }
        String text = sb.substring(0, 10_000);

        List<String> parts = TelegramText.split(text);

        assertEquals(3, parts.size());
        assertEquals(text, String.join("", parts));
        for (String part : parts) {
            assertTrue(part.length() <= TelegramText.MAX_LENGTH, "part of " + part.length());
        }
        assertTrue(parts.get(0).endsWith("\n"));
        assertTrue(parts.get(1).endsWith("\n"));
        assertTrue(parts.get(0).length() > TelegramText.MAX_LENGTH - TelegramText.LINE_BREAK_WINDOW);
    }

    @Test
    void lineBreakJustInsideTheWindowIsUsed() {
        // '\n' at index 3596 = 4096 - 500: the earliest position the window allows.
        String text = "a".repeat(3596) + "\n" + "b".repeat(6000);

        List<String> parts = TelegramText.split(text);

        assertEquals(3597, parts.get(0).length());
        assertTrue(parts.get(0).endsWith("\n"));
        assertEquals(text, String.join("", parts));
    }

    @Test
    void lineBreakBeforeTheWindowIsIgnored() {
        // '\n' at index 3595, one char before the window: hard cut at 4 096.
        String text = "a".repeat(3595) + "\n" + "b".repeat(6000);

        List<String> parts = TelegramText.split(text);

        assertEquals(4096, parts.get(0).length());
        assertEquals(text, String.join("", parts));
    }

    @Test
    void shortTextIsOnePart() {
        assertEquals(List.of("hello\nworld"), TelegramText.split("hello\nworld"));
    }

    @Test
    void textOfExactlyTheLimitIsOnePart() {
        String text = "z".repeat(TelegramText.MAX_LENGTH);
        assertEquals(List.of(text), TelegramText.split(text));
    }

    @Test
    void oneCharOverTheLimitIsTwoParts() {
        String text = "z".repeat(TelegramText.MAX_LENGTH + 1);
        List<String> parts = TelegramText.split(text);
        assertEquals(List.of(4096, 1), parts.stream().map(String::length).toList());
    }

    @Test
    void emptyTextIsOneEmptyPart() {
        assertEquals(List.of(""), TelegramText.split(""));
    }

    @Test
    void aSurrogatePairIsNeverCutInHalf() {
        // An emoji (two chars) straddling the hard limit: indices 4095 and 4096.
        String text = "a".repeat(4095) + "😀" + "b".repeat(100);

        List<String> parts = TelegramText.split(text);

        assertEquals(4095, parts.get(0).length());
        assertFalse(Character.isHighSurrogate(parts.get(0).charAt(parts.get(0).length() - 1)));
        assertEquals(text, String.join("", parts));
    }

    @Test
    void nullTextIsRefused() {
        assertThrows(NullPointerException.class, () -> TelegramText.split(null));
    }
}
