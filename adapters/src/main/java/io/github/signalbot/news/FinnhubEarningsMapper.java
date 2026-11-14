package io.github.signalbot.news;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.signalbot.domain.model.EarningsEvent;
import io.github.signalbot.domain.port.SourceFailureException;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Maps a Finnhub {@code /calendar/earnings} response to {@link EarningsEvent}s. Pure.
 * {@code {"earningsCalendar": []}} is "no events"; a missing body, a missing or
 * non-array {@code earningsCalendar}, or an event without a valid date throws.
 */
@Component
public class FinnhubEarningsMapper {

    static final String SOURCE = "finnhub/calendar-earnings";

    public List<EarningsEvent> toEvents(String symbol, JsonNode root) {
        if (root == null || root.isNull() || root.isMissingNode()) {
            throw new SourceFailureException(SOURCE, "empty response body for " + symbol);
        }
        JsonNode array = root.path("earningsCalendar");
        if (!array.isArray()) {
            throw new SourceFailureException(SOURCE,
                    "missing or non-array 'earningsCalendar' for " + symbol + ": "
                            + FinnhubNewsMapper.abbreviate(root));
        }
        List<EarningsEvent> events = new ArrayList<>();
        for (JsonNode node : array) {
            String date = node.path("date").asText("");
            try {
                events.add(new EarningsEvent(symbol, LocalDate.parse(date)));
            } catch (DateTimeParseException e) {
                throw new SourceFailureException(SOURCE,
                        "earnings event for " + symbol + " has no valid 'date': " + FinnhubNewsMapper.abbreviate(node), e);
            }
        }
        return events;
    }
}
