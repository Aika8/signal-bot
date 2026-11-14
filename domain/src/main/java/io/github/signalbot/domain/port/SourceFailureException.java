package io.github.signalbot.domain.port;

/**
 * An input source (feed, portfolio file, broker reader) failed or returned a
 * malformed response. Distinct from "no data": a port returns an empty result
 * when the source answered and there is genuinely nothing, and throws this when
 * it cannot tell. No verdict may be built on top of a failed source.
 */
public class SourceFailureException extends RuntimeException {

    private final String source;

    public SourceFailureException(String source, String message) {
        this(source, message, null);
    }

    public SourceFailureException(String source, String message, Throwable cause) {
        super(source + ": " + message, cause);
        this.source = source;
    }

    /** Short name of the failed source, e.g. {@code finnhub/company-news}. */
    public String source() {
        return source;
    }
}
