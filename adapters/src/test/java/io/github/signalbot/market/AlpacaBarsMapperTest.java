package io.github.signalbot.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.signalbot.domain.model.DailyBar;
import io.github.signalbot.domain.port.SourceFailureException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlpacaBarsMapperTest {

    private static final String BAR_24 =
            "{\"t\":\"2026-11-24T05:00:00Z\",\"o\":101.5,\"h\":103.25,\"l\":100.75,\"c\":102.5,\"v\":1200300}";
    private static final String BAR_25 =
            "{\"t\":\"2026-11-25T05:00:00Z\",\"o\":102.5,\"h\":104.0,\"l\":101.0,\"c\":103.75,\"v\":980000}";
    private static final String BAR_26 =
            "{\"t\":\"2026-11-26T05:00:00Z\",\"o\":103.75,\"h\":105.0,\"l\":103.0,\"c\":104.5,\"v\":10000}";

    private final ObjectMapper om = new ObjectMapper();
    private final AlpacaBarsMapper mapper = new AlpacaBarsMapper();

    private JsonNode json(String text) throws Exception {
        return om.readTree(text);
    }

    @Test
    void mapsGoodBodyOldestFirst() throws Exception {
        // Newest first, as with sort=desc.
        JsonNode body = json("{\"bars\":[" + BAR_25 + "," + BAR_24 + "],\"symbol\":\"AMD\",\"next_page_token\":null}");

        List<DailyBar> bars = mapper.toBars("AMD", body);

        assertEquals(2, bars.size());
        DailyBar first = bars.get(0);
        assertEquals(LocalDate.of(2026, 11, 24), first.date());
        assertEquals(new BigDecimal("101.5"), first.open());
        assertEquals(new BigDecimal("103.25"), first.high());
        assertEquals(new BigDecimal("100.75"), first.low());
        assertEquals(new BigDecimal("102.5"), first.close());
        assertEquals(1_200_300L, first.volume());
        assertEquals(LocalDate.of(2026, 11, 25), bars.get(1).date());
    }

    @Test
    void timestampWithOffsetBecomesUtcDate() throws Exception {
        JsonNode body = json("{\"bars\":[{\"t\":\"2026-11-24T23:30:00-05:00\",\"o\":1,\"h\":2,\"l\":1,\"c\":2,\"v\":5}]}");

        assertEquals(LocalDate.of(2026, 11, 25), mapper.toBars("AMD", body).get(0).date());
    }

    @Test
    void emptyBarsArrayIsEmptyList() throws Exception {
        assertTrue(mapper.toBars("AMD", json("{\"bars\":[],\"symbol\":\"AMD\"}")).isEmpty());
    }

    @Test
    void nullBarsIsEmptyList() throws Exception {
        assertTrue(mapper.toBars("AMD", json("{\"bars\":null,\"symbol\":\"AMD\"}")).isEmpty());
    }

    @Test
    void missingBarsIsEmptyList() throws Exception {
        assertTrue(mapper.toBars("AMD", json("{\"symbol\":\"AMD\",\"next_page_token\":null}")).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"h", "l", "c", "t", "o", "v"})
    void missingFieldFails(String field) throws Exception {
        var node = (ObjectNode) om.readTree(BAR_24);
        node.remove(field);

        SourceFailureException e = assertThrows(SourceFailureException.class,
                () -> mapper.toBars("AMD", json("{\"bars\":[" + node + "]}")));
        assertEquals("alpaca", e.source());
    }

    @ParameterizedTest
    @ValueSource(strings = {"h", "l", "c"})
    void nonNumericPriceFails(String field) throws Exception {
        var node = (ObjectNode) om.readTree(BAR_24);
        node.put(field, "abc");

        assertThrows(SourceFailureException.class, () -> mapper.toBars("AMD", json("{\"bars\":[" + node + "]}")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"h", "l", "c"})
    void nullPriceFails(String field) throws Exception {
        var node = (ObjectNode) om.readTree(BAR_24);
        node.putNull(field);

        assertThrows(SourceFailureException.class, () -> mapper.toBars("AMD", json("{\"bars\":[" + node + "]}")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"yesterday\"", "\"2026-11-24\"", "12345", "null"})
    void malformedTimestampFails(String t) throws Exception {
        String bar = "{\"t\":" + t + ",\"o\":1,\"h\":2,\"l\":1,\"c\":2,\"v\":5}";

        assertThrows(SourceFailureException.class, () -> mapper.toBars("AMD", json("{\"bars\":[" + bar + "]}")));
    }

    @Test
    void highBelowLowFails() throws Exception {
        String bar = "{\"t\":\"2026-11-24T05:00:00Z\",\"o\":1,\"h\":1,\"l\":2,\"c\":1,\"v\":5}";

        assertThrows(SourceFailureException.class, () -> mapper.toBars("AMD", json("{\"bars\":[" + bar + "]}")));
    }

    @Test
    void oneBadBarFailsTheWholeBody() throws Exception {
        String bad = "{\"t\":\"2026-11-25T05:00:00Z\",\"o\":1,\"l\":1,\"c\":1,\"v\":5}";

        assertThrows(SourceFailureException.class,
                () -> mapper.toBars("AMD", json("{\"bars\":[" + BAR_24 + "," + bad + "]}")));
    }

    @Test
    void missingOrNonObjectBodyFails() throws Exception {
        assertThrows(SourceFailureException.class, () -> mapper.toBars("AMD", null));
        assertThrows(SourceFailureException.class, () -> mapper.toBars("AMD", MissingNode.getInstance()));
        assertThrows(SourceFailureException.class, () -> mapper.toBars("AMD", json("[]")));
        assertThrows(SourceFailureException.class, () -> mapper.toBars("AMD", json("{\"bars\":{}}")));
    }

    @Test
    void completedBarsDropTodayAndKeepTheNewestCount() throws Exception {
        JsonNode body = json("{\"bars\":[" + BAR_26 + "," + BAR_25 + "," + BAR_24 + "]}");

        List<DailyBar> bars = mapper.completedBars("AMD", body, LocalDate.of(2026, 11, 26), 1);

        assertEquals(1, bars.size());
        assertEquals(LocalDate.of(2026, 11, 25), bars.get(0).date());
    }

    @Test
    void completedBarsReturnFewerWhenTheSourceHasFewer() throws Exception {
        JsonNode body = json("{\"bars\":[" + BAR_25 + "," + BAR_24 + "]}");

        List<DailyBar> bars = mapper.completedBars("AMD", body, LocalDate.of(2026, 11, 26), 14);

        assertEquals(List.of(LocalDate.of(2026, 11, 24), LocalDate.of(2026, 11, 25)),
                bars.stream().map(DailyBar::date).toList());
    }
}
