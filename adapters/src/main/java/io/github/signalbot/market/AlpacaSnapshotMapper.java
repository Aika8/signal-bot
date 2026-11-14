package io.github.signalbot.market;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.signalbot.domain.model.PriceSnapshot;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;

/** Maps an Alpaca {@code /v2/stocks/{symbol}/snapshot} response to a {@link PriceSnapshot}. Pure. */
@Component
public class AlpacaSnapshotMapper {

    public PriceSnapshot toSnapshot(String symbol, JsonNode root) {
        BigDecimal last = decimal(root.path("latestTrade").path("p"));
        BigDecimal priorClose = decimal(root.path("prevDailyBar").path("c"));
        String ts = root.path("latestTrade").path("t").asText("");
        Instant at = ts.isBlank() ? Instant.now() : Instant.parse(ts);
        return new PriceSnapshot(symbol, last, priorClose, at);
    }

    private static BigDecimal decimal(JsonNode node) {
        String text = node.asText("");
        return text.isBlank() ? BigDecimal.ZERO : new BigDecimal(text);
    }
}
