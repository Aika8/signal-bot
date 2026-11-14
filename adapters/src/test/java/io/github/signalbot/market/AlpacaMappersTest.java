package io.github.signalbot.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.signalbot.domain.model.PriceSnapshot;
import io.github.signalbot.domain.model.PriceTick;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AlpacaMappersTest {

    private final ObjectMapper om = new ObjectMapper();
    private final AlpacaSnapshotMapper snapshot = new AlpacaSnapshotMapper();
    private final AlpacaStreamDecoder decoder = new AlpacaStreamDecoder();

    @Test
    void mapsSnapshotLastAndPriorClose() throws Exception {
        var root = om.readTree(
                "{\"latestTrade\":{\"p\":150.25,\"t\":\"2026-10-23T15:00:00Z\"},\"prevDailyBar\":{\"c\":148.0}}");
        PriceSnapshot s = snapshot.toSnapshot("AAPL", root);
        assertEquals(0, s.last().compareTo(new BigDecimal("150.25")));
        assertEquals(0, s.priorClose().compareTo(new BigDecimal("148.0")));
    }

    @Test
    void decodesOnlyTradeMessages() throws Exception {
        var frame = om.readTree(
                "[{\"T\":\"success\",\"msg\":\"authenticated\"},"
                        + "{\"T\":\"t\",\"S\":\"AAPL\",\"p\":150.5,\"t\":\"2026-10-23T15:00:00Z\"}]");
        List<PriceTick> ticks = decoder.decode(frame);
        assertEquals(1, ticks.size());
        assertEquals("AAPL", ticks.get(0).symbol());
        assertEquals(0, ticks.get(0).price().compareTo(new BigDecimal("150.5")));
    }
}
