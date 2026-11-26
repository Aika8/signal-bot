package io.github.signalbot.market;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.signalbot.domain.model.DailyBar;
import io.github.signalbot.domain.port.SourceFailureException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Maps an Alpaca {@code /v2/stocks/{symbol}/bars?timeframe=1Day} response to
 * {@link DailyBar}s, oldest first. Pure.
 *
 * <p>Strict (D-001): {@code "bars": null} or a missing {@code bars} field means
 * "no bars" (empty list); a missing body, a non-array {@code bars}, or any bar with
 * a missing or malformed field throws {@link SourceFailureException}. A field is
 * never replaced by zero.
 */
@Component
public class AlpacaBarsMapper {

    static final String SOURCE = "alpaca";

    /** All bars of the body, oldest first. {@code t} (RFC 3339) becomes the bar's date in UTC. */
    public List<DailyBar> toBars(String symbol, JsonNode root) {
        if (root == null || root.isMissingNode() || root.isNull() || !root.isObject()) {
            throw fail(symbol, "bars body is missing or not an object");
        }
        JsonNode bars = root.path("bars");
        if (bars.isMissingNode() || bars.isNull()) {
            return List.of();
        }
        if (!bars.isArray()) {
            throw fail(symbol, "'bars' is not an array");
        }
        List<DailyBar> result = new ArrayList<>(bars.size());
        for (JsonNode bar : bars) {
            result.add(toBar(symbol, bar));
        }
        result.sort(Comparator.comparing(DailyBar::date));
        return List.copyOf(result);
    }

    /**
     * The last {@code count} bars dated strictly before {@code today}, oldest first.
     * A bar dated {@code today} or later may be the still-open session and is dropped.
     */
    public List<DailyBar> completedBars(String symbol, JsonNode root, LocalDate today, int count) {
        List<DailyBar> completed = toBars(symbol, root).stream()
                .filter(b -> b.date().isBefore(today))
                .toList();
        int from = Math.max(0, completed.size() - count);
        return completed.subList(from, completed.size());
    }

    private static DailyBar toBar(String symbol, JsonNode bar) {
        if (!bar.isObject()) {
            throw fail(symbol, "bar is not an object: " + bar);
        }
        LocalDate date = date(symbol, bar.path("t"));
        try {
            return new DailyBar(date,
                    price(symbol, bar, "o", date),
                    price(symbol, bar, "h", date),
                    price(symbol, bar, "l", date),
                    price(symbol, bar, "c", date),
                    volume(symbol, bar, date));
        } catch (IllegalArgumentException e) {
            throw new SourceFailureException(SOURCE, symbol + ": invalid bar on " + date + ": " + e.getMessage(), e);
        }
    }

    private static LocalDate date(String symbol, JsonNode t) {
        if (!t.isTextual() || t.asText().isBlank()) {
            throw fail(symbol, "bar field 't' is missing or not a timestamp: " + t);
        }
        try {
            return OffsetDateTime.parse(t.asText()).atZoneSameInstant(ZoneOffset.UTC).toLocalDate();
        } catch (DateTimeException e) {
            throw new SourceFailureException(SOURCE, symbol + ": bar field 't' is not RFC 3339: " + t.asText(), e);
        }
    }

    private static BigDecimal price(String symbol, JsonNode bar, String field, LocalDate date) {
        JsonNode node = bar.path(field);
        if (!node.isNumber()) {
            throw fail(symbol, "bar field '" + field + "' on " + date + " is missing or not a number: " + node);
        }
        return new BigDecimal(node.asText());
    }

    private static long volume(String symbol, JsonNode bar, LocalDate date) {
        JsonNode node = bar.path("v");
        if (!node.isIntegralNumber() || !node.canConvertToLong()) {
            throw fail(symbol, "bar field 'v' on " + date + " is missing or not a whole number: " + node);
        }
        return node.asLong();
    }

    private static SourceFailureException fail(String symbol, String message) {
        return new SourceFailureException(SOURCE, symbol + ": " + message);
    }
}
