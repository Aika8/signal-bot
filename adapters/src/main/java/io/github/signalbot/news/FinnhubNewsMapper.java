package io.github.signalbot.news;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.signalbot.domain.model.NewsItem;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Maps a Finnhub {@code /company-news} JSON array to {@link NewsItem}s. Pure. */
@Component
public class FinnhubNewsMapper {

    public List<NewsItem> toNewsItems(String symbol, JsonNode array, Instant since) {
        if (array == null || !array.isArray()) {
            return List.of();
        }
        List<NewsItem> items = new ArrayList<>();
        for (JsonNode node : array) {
            Instant ts = Instant.ofEpochSecond(node.path("datetime").asLong(0));
            if (ts.isBefore(since)) {
                continue;
            }
            items.add(new NewsItem(
                    symbol,
                    node.path("headline").asText(""),
                    node.path("source").asText(""),
                    ts,
                    node.path("url").asText("")));
        }
        return items;
    }
}
