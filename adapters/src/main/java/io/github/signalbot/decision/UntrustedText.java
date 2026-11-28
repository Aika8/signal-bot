package io.github.signalbot.decision;

/**
 * Cleans text that comes from outside the system (news headlines and sources, the trigger reason) before it enters
 * the model prompt (D-009). The result is one line, cannot open or close a tag and is at most {@link #MAX_CODE_POINTS}
 * code points long. Pure.
 */
public final class UntrustedText {

    /** Longest cleaned text, in code points, including the {@link #ELLIPSIS} when cut. */
    public static final int MAX_CODE_POINTS = 200;
    public static final String ELLIPSIS = "…";

    private UntrustedText() {
    }

    /**
     * Line breaks become a space, other control characters are dropped, {@code <} and {@code >} become {@code ‹}
     * and {@code ›}, the ends are trimmed; longer than {@link #MAX_CODE_POINTS} code points is cut and ends in
     * {@code …}. {@code null} gives an empty string.
     */
    public static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        raw.codePoints().forEach(cp -> {
            if (isLineBreak(cp)) {
                sb.append(' ');
            } else if (Character.isISOControl(cp)) {
                // dropped
            } else if (cp == '<') {
                sb.append('‹');
            } else if (cp == '>') {
                sb.append('›');
            } else {
                sb.appendCodePoint(cp);
            }
        });
        String cleaned = sb.toString().strip();
        if (cleaned.codePointCount(0, cleaned.length()) <= MAX_CODE_POINTS) {
            return cleaned;
        }
        int end = cleaned.offsetByCodePoints(0, MAX_CODE_POINTS - 1);
        return cleaned.substring(0, end) + ELLIPSIS;
    }

    private static boolean isLineBreak(int cp) {
        return cp == '\n' || cp == '\r' || cp == 0x0B || cp == 0x0C || cp == 0x85 || cp == 0x2028 || cp == 0x2029;
    }
}
