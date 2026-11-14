package io.github.signalbot.news;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.signalbot.domain.model.EarningsEvent;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Maps a Finnhub {@code /calendar/earnings} response to {@link EarningsEvent}s. Pure. */
@Component
public class FinnhubEarningsMapper {

    public List<EarningsEvent> toEvents(String symbol, JsonNode root) {
        if (root == null) {
            return List.of();
        }
        JsonNode array = root.path("earningsCalendar");
        if (!array.isArray()) {
            return List.of();
        }
        List<EarningsEvent> events = new ArrayList<>();
        for (JsonNode node : array) {
            String date = node.path("date").asText("");
            if (date.isBlank()) {
                continue;
            }
            events.add(new EarningsEvent(symbol, LocalDate.parse(date)));
        }
        return events;
    }
}
