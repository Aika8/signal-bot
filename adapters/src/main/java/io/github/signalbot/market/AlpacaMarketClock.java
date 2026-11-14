package io.github.signalbot.market;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.signalbot.domain.model.Venue;
import io.github.signalbot.domain.port.MarketClock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * US market clock backed by Alpaca {@code /v2/clock}. Increment 3 supports the US
 * venue only; other venues report closed. Loaded only when {@code signalbot.alpaca.enabled=true}.
 */
@Component
@ConditionalOnProperty(prefix = "signalbot.alpaca", name = "enabled", havingValue = "true")
public class AlpacaMarketClock implements MarketClock {

    private final RestClient trading;

    public AlpacaMarketClock(RestClient alpacaTradingRestClient) {
        this.trading = alpacaTradingRestClient;
    }

    @Override
    @Retry(name = "alpaca")
    @CircuitBreaker(name = "alpaca")
    public boolean isOpen(Venue venue, Instant now) {
        return venue == Venue.US && clock().path("is_open").asBoolean(false);
    }

    @Override
    @Retry(name = "alpaca")
    @CircuitBreaker(name = "alpaca")
    public Instant nextOpen(Venue venue, Instant now) {
        return parse(clock().path("next_open").asText(""));
    }

    @Override
    @Retry(name = "alpaca")
    @CircuitBreaker(name = "alpaca")
    public Instant nextClose(Venue venue, Instant now) {
        return parse(clock().path("next_close").asText(""));
    }

    private JsonNode clock() {
        return trading.get().uri("/v2/clock").retrieve().body(JsonNode.class);
    }

    private static Instant parse(String rfc3339) {
        return rfc3339.isBlank() ? Instant.now() : OffsetDateTime.parse(rfc3339).toInstant();
    }
}
