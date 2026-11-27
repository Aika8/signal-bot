package io.github.signalbot.telegram;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The one place that cuts text into Telegram-sized parts (SB-039, issue #37). Telegram refuses a message longer than
 * {@value #MAX_LENGTH} characters, so a long text is split into parts of at most that length. A part ends after the
 * last line break within its final {@value #LINE_BREAK_WINDOW} characters when there is one, otherwise at the hard
 * limit. Nothing is lost or added: the parts joined back together are exactly the original text (a line break stays
 * at the end of the part it closes). A cut never separates the two halves of a surrogate pair.
 *
 * <p>Pure: no Telegram call. The caller loops over the parts and sends each with its own call, so a retry of one
 * part never resends the parts before it.
 */
public final class TelegramText {

    /** Telegram's limit on the length of one message, in Java {@code char}s (UTF-16 code units). */
    public static final int MAX_LENGTH = 4096;

    /** How far back from the hard limit a line break is looked for. */
    public static final int LINE_BREAK_WINDOW = 500;

    private TelegramText() {
    }

    /** Splits {@code text} into parts of at most {@link #MAX_LENGTH} chars; a short (or empty) text is one part. */
    public static List<String> split(String text) {
        Objects.requireNonNull(text, "text");
        List<String> parts = new ArrayList<>();
        int start = 0;
        while (text.length() - start > MAX_LENGTH) {
            int end = cut(text, start);
            parts.add(text.substring(start, end));
            start = end;
        }
        parts.add(text.substring(start));
        return parts;
    }

    /** End (exclusive) of the part starting at {@code start}; only called when the rest exceeds the limit. */
    private static int cut(String text, int start) {
        int limit = start + MAX_LENGTH;
        int lineBreak = text.lastIndexOf('\n', limit - 1);
        if (lineBreak >= limit - LINE_BREAK_WINDOW && lineBreak >= start) {
            return lineBreak + 1;
        }
        if (Character.isHighSurrogate(text.charAt(limit - 1)) && Character.isLowSurrogate(text.charAt(limit))) {
            return limit - 1;
        }
        return limit;
    }
}
