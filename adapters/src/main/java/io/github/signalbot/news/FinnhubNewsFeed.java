package io.github.signalbot.news;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.signalbot.config.FinnhubProperties;
import io.github.signalbot.domain.model.NewsItem;
import io.github.signalbot.domain.port.NewsFeed;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/** Finnhub company-news adapter. Loaded only when {@code signalbot.finnhub.enabled=true}. */
@Component
@ConditionalOnProperty(prefix = "signalbot.finnhub", name = "enabled", havingValue = "true")
public class FinnhubNewsFeed implements NewsFeed {

    private final RestClient finnhub;
    private final FinnhubProperties props;
    private final FinnhubNewsMapper mapper;

    public FinnhubNewsFeed(RestClient finnhubRestClient, FinnhubProperties props, FinnhubNewsMapper mapper) {
        this.finnhub = finnhubRestClient;
        this.props = props;
        this.mapper = mapper;
    }

    @Override
    @RateLimiter(name = "finnhub")
    @Retry(name = "finnhub")
    @CircuitBreaker(name = "finnhub")
    public List<NewsItem> newsSince(String symbol, Instant since) {
        LocalDate from = LocalDate.ofInstant(since, ZoneOffset.UTC);
        LocalDate to = LocalDate.now(ZoneOffset.UTC);
        JsonNode body = finnhub.get()
                .uri(uri -> uri.path("/company-news")
                        .queryParam("symbol", symbol)
                        .queryParam("from", from)
                        .queryParam("to", to)
                        .queryParam("token", props.token())
                        .build())
                .retrieve()
                .body(JsonNode.class);
        return mapper.toNewsItems(symbol, body, since);
    }
}
