package io.github.signalbot.market;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.signalbot.domain.model.PriceTick;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Decodes an Alpaca stream frame (a JSON array of messages) into price ticks.
 * Only trade messages ({@code "T":"t"}) become ticks; control frames are ignored. Pure.
 */
@Component
public class AlpacaStreamDecoder {

    public List<PriceTick> decode(JsonNode frame) {
        if (frame == null || !frame.isArray()) {
            return List.of();
        }
        List<PriceTick> ticks = new ArrayList<>();
        for (JsonNode msg : frame) {
            if (!"t".equals(msg.path("T").asText(""))) {
                continue;
            }
            String symbol = msg.path("S").asText("");
            String price = msg.path("p").asText("");
            if (symbol.isBlank() || price.isBlank()) {
                continue;
            }
            String ts = msg.path("t").asText("");
            Instant at = ts.isBlank() ? Instant.now() : Instant.parse(ts);
            ticks.add(new PriceTick(symbol, new BigDecimal(price), at));
        }
        return ticks;
    }
}
