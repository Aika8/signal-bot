package io.github.signalbot.news;

import io.github.signalbot.config.FinnhubProperties;
import io.github.signalbot.domain.model.NewsItem;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class FinnhubNewsFeedTest {

    @Test
    void fetchesAndMapsCompanyNews() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://finnhub.io/api/v1");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();
        String json = "[{\"datetime\":1760000000,\"headline\":\"Buyback\",\"source\":\"Reuters\",\"url\":\"http://x\"}]";
        server.expect(requestTo(containsString("/company-news")))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        FinnhubNewsFeed feed = new FinnhubNewsFeed(
                client,
                new FinnhubProperties(true, "token", "https://finnhub.io/api/v1"),
                new FinnhubNewsMapper());

        List<NewsItem> items = feed.newsSince("AAPL", Instant.ofEpochSecond(1));

        assertEquals(1, items.size());
        assertEquals("Buyback", items.get(0).headline());
        server.verify();
    }
}
