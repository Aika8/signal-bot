package io.github.signalbot.market;

import io.github.signalbot.config.FinnhubProperties;
import io.github.signalbot.screen.ScreenProperties;
import io.github.signalbot.screen.ScreenRunner;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** SB-041 / D-008: every daily-bars request asks for split-adjusted bars from the SIP feed and sends no end. */
class AlpacaBarsAdjustmentTest {

    private static final String BASE = "https://data.alpaca.markets";

    private static String bars() {
        return "{\"bars\":["
                + "{\"t\":\"2026-11-26T05:00:00Z\",\"o\":11,\"h\":11,\"l\":11,\"c\":11,\"v\":1000000},"
                + "{\"t\":\"2026-11-25T05:00:00Z\",\"o\":10,\"h\":10,\"l\":10,\"c\":10,\"v\":1000000}]}";
    }

    @Test
    void priceHistoryRequestsSplitAdjustedSipBars() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(containsString("/v2/stocks/AAPL/bars")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("adjustment", "split"))
                .andExpect(queryParam("feed", "sip"))
                .andExpect(request -> assertFalse(UriComponentsBuilder.fromUri(request.getURI()).build()
                        .getQueryParams().containsKey("end"), "no end parameter (D-008)"))
                .andRespond(withSuccess(bars(), MediaType.APPLICATION_JSON));
        AlpacaPriceHistory history = new AlpacaPriceHistory(builder.build(), new AlpacaBarsMapper(),
                Clock.fixed(Instant.parse("2026-11-27T15:00:00Z"), ZoneOffset.UTC));

        assertEquals(2, history.dailyBars("AAPL", 2).size());
        server.verify();
    }

    @Test
    void screenerRequestsSplitAdjustedSipBars() throws Exception {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        server.expect(requestTo(containsString("/v2/stocks/AAPL/snapshot")))
                .andRespond(withSuccess("{\"latestTrade\":{\"p\":11,\"t\":\"2026-11-27T15:00:00Z\"},"
                        + "\"prevDailyBar\":{\"c\":11}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/v2/stocks/AAPL/bars")))
                .andExpect(queryParam("adjustment", "split"))
                .andExpect(queryParam("feed", "sip"))
                .andRespond(withSuccess(bars(), MediaType.APPLICATION_JSON));
        // The Finnhub client has no base URL: the earnings lookup fails and the runner only prints an error row.
        ScreenRunner runner = new ScreenRunner(builder.build(), RestClient.builder().build(),
                new FinnhubProperties(false, "token", "https://finnhub.io/api/v1"),
                new ScreenProperties(List.of("AAPL"), null));

        runner.run();

        server.verify();
    }
}
