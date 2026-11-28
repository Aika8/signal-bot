package io.github.signalbot.screen;

import io.github.signalbot.config.FinnhubProperties;
import io.github.signalbot.domain.model.DailyBar;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** SB-040: the screener asks Alpaca for 60 trading days of history and drops today's unfinished bar. */
class ScreenRunnerBarsQueryTest {

    private static final String BASE = "https://data.alpaca.markets";

    private final RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    private ScreenRunner runner(Instant now) {
        return new ScreenRunner(builder.build(), RestClient.builder().build(),
                new FinnhubProperties(false, "token", "https://finnhub.io/api/v1"),
                new ScreenProperties(List.of("AAPL"), null),
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private static String bar(String date, String close) {
        return "{\"t\":\"" + date + "T04:00:00Z\",\"o\":" + close + ",\"h\":" + close + ",\"l\":" + close
                + ",\"c\":" + close + ",\"v\":1000000}";
    }

    @Test
    void requestCarriesStartLimitAndSortAsAlpacaPriceHistoryComputesThem() {
        // Friday 2026-11-27, 10:00 in New York: today = 2026-11-27, start = today - (2 * 60 + 10) days.
        ScreenRunner runner = runner(Instant.parse("2026-11-27T15:00:00Z"));
        server.expect(requestTo(containsString("/v2/stocks/AAPL/bars")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("timeframe", "1Day"))
                .andExpect(queryParam("start", "2026-07-20"))
                .andExpect(queryParam("limit", "61"))
                .andExpect(queryParam("sort", "desc"))
                .andRespond(withSuccess("{\"bars\":[" + bar("2026-11-27", "12") + "," + bar("2026-11-26", "11")
                        + "," + bar("2026-11-25", "10") + "]}", MediaType.APPLICATION_JSON));

        List<DailyBar> bars = runner.dailyBars("AAPL");

        server.verify();
        assertEquals(List.of(LocalDate.parse("2026-11-25"), LocalDate.parse("2026-11-26")),
                bars.stream().map(DailyBar::date).toList(), "today's unfinished bar is dropped, oldest first");
        assertEquals(new BigDecimal("11"), bars.get(1).close());
    }

    @Test
    void todayIsTheNewYorkDateEvenAfterUtcMidnight() {
        // 2026-11-28T02:00Z is still 2026-11-27 (21:00) in New York.
        ScreenRunner runner = runner(Instant.parse("2026-11-28T02:00:00Z"));
        server.expect(requestTo(containsString("/v2/stocks/AAPL/bars")))
                .andExpect(queryParam("start", "2026-07-20"))
                .andRespond(withSuccess("{\"bars\":[" + bar("2026-11-27", "12") + "," + bar("2026-11-26", "11")
                        + "]}", MediaType.APPLICATION_JSON));

        List<DailyBar> bars = runner.dailyBars("AAPL");

        server.verify();
        assertEquals(List.of(LocalDate.parse("2026-11-26")), bars.stream().map(DailyBar::date).toList());
    }

    @Test
    void keepsAtMostSixtyCompletedBars() {
        ScreenRunner runner = runner(Instant.parse("2026-11-27T15:00:00Z"));
        StringBuilder json = new StringBuilder("{\"bars\":[");
        LocalDate d = LocalDate.parse("2026-11-27");
        for (int i = 0; i < 61; i++) {
            json.append(i == 0 ? "" : ",").append(bar(d.minusDays(i).toString(), "10"));
        }
        json.append("]}");
        server.expect(requestTo(containsString("/v2/stocks/AAPL/bars")))
                .andRespond(withSuccess(json.toString(), MediaType.APPLICATION_JSON));

        List<DailyBar> bars = runner.dailyBars("AAPL");

        assertEquals(ScreenRunner.BARS, bars.size());
        assertEquals(LocalDate.parse("2026-11-26"), bars.get(bars.size() - 1).date());
    }
}
