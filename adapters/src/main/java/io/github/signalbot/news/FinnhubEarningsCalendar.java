package io.github.signalbot.news;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.signalbot.config.FinnhubProperties;
import io.github.signalbot.domain.model.EarningsEvent;
import io.github.signalbot.domain.port.EarningsCalendar;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;

/** Finnhub earnings-calendar adapter. Loaded only when {@code signalbot.finnhub.enabled=true}. */
@Component
@ConditionalOnProperty(prefix = "signalbot.finnhub", name = "enabled", havingValue = "true")
public class FinnhubEarningsCalendar implements EarningsCalendar {

    private final RestClient finnhub;
    private final FinnhubProperties props;
    private final FinnhubEarningsMapper mapper;

    public FinnhubEarningsCalendar(RestClient finnhubRestClient, FinnhubProperties props, FinnhubEarningsMapper mapper) {
        this.finnhub = finnhubRestClient;
        this.props = props;
        this.mapper = mapper;
    }

    @Override
    @RateLimiter(name = "finnhub")
    @Retry(name = "finnhub")
    @CircuitBreaker(name = "finnhub")
    public List<EarningsEvent> upcoming(String symbol, LocalDate from, LocalDate to) {
        JsonNode body = finnhub.get()
                .uri(uri -> uri.path("/calendar/earnings")
                        .queryParam("symbol", symbol)
                        .queryParam("from", from)
                        .queryParam("to", to)
                        .queryParam("token", props.token())
                        .build())
                .retrieve()
                .body(JsonNode.class);
        return mapper.toEvents(symbol, body);
    }
}
