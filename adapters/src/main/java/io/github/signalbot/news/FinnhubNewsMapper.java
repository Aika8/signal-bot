package io.github.signalbot.news;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.signalbot.domain.model.NewsItem;
import io.github.signalbot.domain.port.SourceFailureException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Maps a Finnhub {@code /company-news} JSON array to {@link NewsItem}s. Pure.
 * An empty array is "no news"; a missing body, a non-array body or an item
 * without a timestamp is a malformed response and throws.
 */
@Component
public class FinnhubNewsMapper {

    static final String SOURCE = "finnhub/company-news";

    public List<NewsItem> toNewsItems(String symbol, JsonNode array, Instant since) {
        if (array == null || array.isNull() || array.isMissingNode()) {
            throw new SourceFailureException(SOURCE, "empty response body for " + symbol);
        }
        if (!array.isArray()) {
            throw new SourceFailureException(SOURCE,
                    "expected a JSON array for " + symbol + ", got " + array.getNodeType() + ": " + abbreviate(array));
        }
        List<NewsItem> items = new ArrayList<>();
        for (JsonNode node : array) {
            JsonNode datetime = node.path("datetime");
            if (!datetime.canConvertToLong() || datetime.asLong() <= 0) {
                throw new SourceFailureException(SOURCE,
                        "news item for " + symbol + " has no valid 'datetime': " + abbreviate(node));
            }
            Instant ts = Instant.ofEpochSecond(datetime.asLong());
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

    static String abbreviate(JsonNode node) {
        String s = node.toString();
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }
}
