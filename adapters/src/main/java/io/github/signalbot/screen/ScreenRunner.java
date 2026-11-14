package io.github.signalbot.screen;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.signalbot.config.FinnhubProperties;
import io.github.signalbot.domain.model.EarningsEvent;
import io.github.signalbot.market.AlpacaSnapshotMapper;
import io.github.signalbot.news.FinnhubEarningsMapper;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** One-off screener. Loads metrics per symbol sequentially and prints a PASS/FAIL table. */
@Component
public class ScreenRunner implements CommandLineRunner {

    private record Row(String symbol, BigDecimal price, BigDecimal adv, BigDecimal atr, String earnings, boolean pass) {
    }

    private final RestClient alpaca;
    private final RestClient finnhub;
    private final FinnhubProperties finnhubProps;
    private final ScreenProperties screen;
    private final AlpacaSnapshotMapper snapshotMapper = new AlpacaSnapshotMapper();
    private final FinnhubEarningsMapper earningsMapper = new FinnhubEarningsMapper();

    public ScreenRunner(RestClient alpacaDataRestClient, RestClient finnhubRestClient,
                        FinnhubProperties finnhubProps, ScreenProperties screen) {
        this.alpaca = alpacaDataRestClient;
        this.finnhub = finnhubRestClient;
        this.finnhubProps = finnhubProps;
        this.screen = screen;
    }

    @Override
    public void run(String... args) throws Exception {
        List<Row> rows = new ArrayList<>();
        for (String symbol : screen.candidates()) {
            try {
                BigDecimal last = lastPrice(symbol);
                List<Bar> bars = dailyBars(symbol);
                BigDecimal adv = ScreenMetrics.advMillions(bars);
                BigDecimal atr = ScreenMetrics.atrPercent(bars, last);
                boolean pass = ScreenMetrics.pass(last, adv, atr, screen.maxPrice());
                rows.add(new Row(symbol, last, adv, atr, nextEarnings(symbol), pass));
            } catch (Exception e) {
                System.out.printf("%-6s ERROR %s%n", symbol, e.getMessage());
            }
            Thread.sleep(1100);   // sequential; keeps Finnhub under 60 req/min
        }
        rows.sort(Comparator.comparing(Row::atr).reversed());
        System.out.printf("%-6s %10s %8s %7s %12s %s%n", "SYMBOL", "PRICE", "ADV(M)", "ATR%", "EARNINGS", "RESULT");
        long passes = 0;
        for (Row r : rows) {
            System.out.printf("%-6s %10s %8s %7s %12s %s%n", r.symbol(), r.price().toPlainString(),
                    r.adv().toPlainString(), r.atr().toPlainString(), r.earnings(), r.pass() ? "PASS" : "FAIL");
            if (r.pass()) {
                passes++;
            }
        }
        System.out.println("PASS: " + passes);
    }

    private BigDecimal lastPrice(String symbol) {
        JsonNode node = alpaca.get().uri("/v2/stocks/{s}/snapshot", symbol).retrieve().body(JsonNode.class);
        return snapshotMapper.toSnapshot(symbol, node).last();
    }

    private List<Bar> dailyBars(String symbol) {
        JsonNode node = alpaca.get()
                .uri(b -> b.path("/v2/stocks/{s}/bars").queryParam("timeframe", "1Day").queryParam("limit", 60).build(symbol))
                .retrieve().body(JsonNode.class);
        List<Bar> bars = new ArrayList<>();
        for (JsonNode e : node.path("bars")) {
            bars.add(new Bar(new BigDecimal(e.path("h").asText("0")), new BigDecimal(e.path("l").asText("0")),
                    new BigDecimal(e.path("c").asText("0")), e.path("v").asLong(0)));
        }
        return bars;
    }

    private String nextEarnings(String symbol) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate to = today.plusDays(30);
        JsonNode node = finnhub.get()
                .uri(b -> b.path("/calendar/earnings").queryParam("symbol", symbol)
                        .queryParam("from", today).queryParam("to", to)
                        .queryParam("token", finnhubProps.token()).build())
                .retrieve().body(JsonNode.class);
        return earningsMapper.toEvents(symbol, node).stream()
                .map(EarningsEvent::date)
                .filter(d -> !d.isBefore(today) && !d.isAfter(to))
                .sorted()
                .findFirst()
                .map(LocalDate::toString)
                .orElse("-");
    }
}
