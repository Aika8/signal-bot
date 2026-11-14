package io.github.signalbot.news;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.signalbot.domain.model.EarningsEvent;
import io.github.signalbot.domain.model.NewsItem;
import io.github.signalbot.domain.port.SourceFailureException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FinnhubMapperTest {

    private final ObjectMapper om = new ObjectMapper();
    private final FinnhubNewsMapper news = new FinnhubNewsMapper();
    private final FinnhubEarningsMapper earnings = new FinnhubEarningsMapper();

    @Test
    void mapsNewsAndFiltersBySince() throws Exception {
        var arr = om.readTree("[{\"datetime\":1760000000,\"headline\":\"Buyback\",\"source\":\"Reuters\",\"url\":\"http://x\"}]");
        List<NewsItem> items = news.toNewsItems("AAPL", arr, Instant.ofEpochSecond(1));
        assertEquals(1, items.size());
        assertEquals("Buyback", items.get(0).headline());
        assertTrue(news.toNewsItems("AAPL", arr, Instant.ofEpochSecond(1_760_000_001L)).isEmpty());
    }

    @Test
    void emptyNewsArrayIsNoNews() throws Exception {
        assertTrue(news.toNewsItems("AAPL", om.readTree("[]"), Instant.EPOCH).isEmpty());
    }

    @Test
    void malformedNewsResponseFails() throws Exception {
        assertThrows(SourceFailureException.class, () -> news.toNewsItems("AAPL", null, Instant.EPOCH));
        var e = assertThrows(SourceFailureException.class,
                () -> news.toNewsItems("AAPL", om.readTree("{\"error\":\"API limit reached\"}"), Instant.EPOCH));
        assertTrue(e.getMessage().contains("API limit reached"));
        assertEquals("finnhub/company-news", e.source());
        assertThrows(SourceFailureException.class,
                () -> news.toNewsItems("AAPL", om.readTree("[{\"headline\":\"no time\"}]"), Instant.EPOCH));
    }

    @Test
    void mapsEarningsAndEmptyCalendarIsNoEvents() throws Exception {
        var root = om.readTree("{\"earningsCalendar\":[{\"date\":\"2026-10-25\",\"symbol\":\"AAPL\"}]}");
        List<EarningsEvent> events = earnings.toEvents("AAPL", root);
        assertEquals(LocalDate.of(2026, 10, 25), events.get(0).date());
        assertTrue(earnings.toEvents("AAPL", om.readTree("{\"earningsCalendar\":[]}")).isEmpty());
    }

    @Test
    void malformedEarningsResponseFails() throws Exception {
        assertThrows(SourceFailureException.class, () -> earnings.toEvents("AAPL", null));
        assertThrows(SourceFailureException.class, () -> earnings.toEvents("AAPL", om.readTree("{}")));
        assertThrows(SourceFailureException.class,
                () -> earnings.toEvents("AAPL", om.readTree("{\"earningsCalendar\":[{\"date\":\"\"}]}")));
    }
}
