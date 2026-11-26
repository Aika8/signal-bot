package io.github.signalbot.market;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.signalbot.domain.model.DailyBar;
import io.github.signalbot.domain.port.PriceHistory;
import io.github.signalbot.domain.port.SourceFailureException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Daily bars from Alpaca {@code /v2/stocks/{symbol}/bars?timeframe=1Day}. Loaded only
 * when {@code signalbot.alpaca.enabled=true}. Parsing is delegated to
 * {@link AlpacaBarsMapper} (unit-tested); this class owns only the HTTP call.
 *
 * <p>Asks for the newest {@code count + 1} bars since a look-back start, then drops a
 * bar dated today (America/New_York): it may be the still-open session.
 */
@Component
@ConditionalOnProperty(prefix = "signalbot.alpaca", name = "enabled", havingValue = "true")
public class AlpacaPriceHistory implements PriceHistory {

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private final RestClient data;
    private final AlpacaBarsMapper mapper;
    private final Clock clock;

    @Autowired
    public AlpacaPriceHistory(RestClient alpacaDataRestClient, AlpacaBarsMapper mapper) {
        this(alpacaDataRestClient, mapper, Clock.systemUTC());
    }

    AlpacaPriceHistory(RestClient alpacaDataRestClient, AlpacaBarsMapper mapper, Clock clock) {
        this.data = alpacaDataRestClient;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    @Retry(name = "alpaca")
    @CircuitBreaker(name = "alpaca")
    public List<DailyBar> dailyBars(String symbol, int count) {
        if (count < 1) {
            throw new IllegalArgumentException("count must be positive: " + count);
        }
        LocalDate today = LocalDate.now(clock.withZone(NEW_YORK));
        // Calendar days that surely hold count trading days (weekends, holidays).
        LocalDate start = today.minusDays(2L * count + 10);
        JsonNode body;
        try {
            body = data.get()
                    .uri(b -> b.path("/v2/stocks/{s}/bars")
                            .queryParam("timeframe", "1Day")
                            .queryParam("start", start)
                            .queryParam("limit", count + 1)
                            .queryParam("sort", "desc")
                            .build(symbol))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            throw new SourceFailureException(AlpacaBarsMapper.SOURCE,
                    symbol + ": daily bars request failed: " + e.getMessage(), e);
        }
        return mapper.completedBars(symbol, body, today, count);
    }
}
