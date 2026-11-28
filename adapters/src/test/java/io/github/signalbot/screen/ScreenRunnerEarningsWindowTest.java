package io.github.signalbot.screen;

import io.github.signalbot.config.FinnhubProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** AUTO-20261127-6: the screener's earnings window starts at the New York date, not the UTC date. */
class ScreenRunnerEarningsWindowTest {

    private static final String BASE = "https://finnhub.io/api/v1";

    private final RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    private ScreenRunner runner(Instant now) {
        return new ScreenRunner(RestClient.builder().build(), builder.build(),
                new FinnhubProperties(false, "token", BASE),
                new ScreenProperties(List.of("AAPL"), null),
                Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void windowStartsAtNewYorkDateAfterUtcMidnight() {
        // 2026-11-28T02:00Z is still 2026-11-27 (21:00) in New York.
        ScreenRunner runner = runner(Instant.parse("2026-11-28T02:00:00Z"));
        server.expect(requestTo(containsString("/calendar/earnings")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("symbol", "AAPL"))
                .andExpect(queryParam("from", "2026-11-27"))
                .andExpect(queryParam("to", "2026-12-27"))
                .andRespond(withSuccess("{\"earningsCalendar\":[{\"date\":\"2026-11-27\",\"symbol\":\"AAPL\"}]}",
                        MediaType.APPLICATION_JSON));

        String next = runner.nextEarnings("AAPL");

        server.verify();
        assertEquals("2026-11-27", next, "an event dated today in New York is not missed");
    }

    @Test
    void windowStartsAtNewYorkDateDuringTheSession() {
        // 2026-11-27T15:00Z is 10:00 in New York, same date in both zones.
        ScreenRunner runner = runner(Instant.parse("2026-11-27T15:00:00Z"));
        server.expect(requestTo(containsString("/calendar/earnings")))
                .andExpect(queryParam("from", "2026-11-27"))
                .andExpect(queryParam("to", "2026-12-27"))
                .andRespond(withSuccess("{\"earningsCalendar\":[]}", MediaType.APPLICATION_JSON));

        String next = runner.nextEarnings("AAPL");

        server.verify();
        assertEquals("-", next, "no events means no date");
    }
}
