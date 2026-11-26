package io.github.signalbot.decision;

import io.github.signalbot.domain.model.DailyBar;
import io.github.signalbot.domain.model.DecisionContext;
import io.github.signalbot.domain.model.Portfolio;
import io.github.signalbot.domain.model.PriceSnapshot;
import io.github.signalbot.domain.port.CashReader;
import io.github.signalbot.domain.port.MarketDataFeed;
import io.github.signalbot.domain.port.NewsFeed;
import io.github.signalbot.domain.port.PortfolioReader;
import io.github.signalbot.domain.port.PriceHistory;
import io.github.signalbot.domain.port.SourceFailureException;
import io.github.signalbot.trigger.ContextAssembler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** SB-011: the ATR line of the Claude prompt and how {@link ContextAssembler} fills it. */
class ClaudePromptAtrTest {

    private final ClaudePromptBuilder builder = new ClaudePromptBuilder();
    private final PortfolioReader portfolio = mock(PortfolioReader.class);
    private final CashReader cash = mock(CashReader.class);
    private final MarketDataFeed market = mock(MarketDataFeed.class);
    private final PriceHistory history = mock(PriceHistory.class);

    private static DecisionContext context(BigDecimal last, BigDecimal atr) {
        PriceSnapshot snapshot = last == null ? null : new PriceSnapshot("AAPL", last, last, Instant.now());
        return new DecisionContext("AAPL", "news", null, List.of(), List.of(), snapshot, null, true, atr);
    }

    private static List<String> lines(String prompt) {
        return prompt.lines().toList();
    }

    @Test
    void atrLineShowsValueAndPercentOfLast() {
        String u = builder.userPrompt(context(new BigDecimal("176"), new BigDecimal("3.700000")));
        assertTrue(lines(u).contains("ATR(14): 3.70 (2.1% of last)"), u);
    }

    @Test
    void atrIsRoundedHalfUpToTwoDecimals() {
        String u = builder.userPrompt(context(new BigDecimal("100"), new BigDecimal("2.345000")));
        assertTrue(lines(u).contains("ATR(14): 2.35 (2.3% of last)"), u);
    }

    @Test
    void missingAtrIsNotAvailable() {
        String u = builder.userPrompt(context(new BigDecimal("176"), null));
        assertTrue(lines(u).contains("ATR(14): not available"), u);
    }

    @Test
    void oldConstructorMeansNotAvailable() {
        DecisionContext ctx = new DecisionContext("AAPL", "news", null, List.of(), List.of(), null, null, true);
        assertNull(ctx.atr());
        assertTrue(lines(builder.userPrompt(ctx)).contains("ATR(14): not available"));
    }

    @Test
    void atrWithoutLastPriceHasNoPercent() {
        String u = builder.userPrompt(context(null, new BigDecimal("3.7")));
        assertTrue(lines(u).contains("ATR(14): 3.70"), u);
    }

    @Test
    void systemPromptStatesTheStopBand() {
        String s = builder.systemPrompt();
        assertTrue(s.contains("1.5-3 ATR below entry"), s);
        assertTrue(s.contains("2 ATR"), s);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }

    private ContextAssembler assembler(PriceHistory h) {
        when(portfolio.read()).thenReturn(new Portfolio(List.of(), BigDecimal.TEN, Map.of(), Instant.now()));
        when(cash.read()).thenReturn(List.of());
        when(market.snapshot("AAPL")).thenReturn(
                new PriceSnapshot("AAPL", new BigDecimal("100"), new BigDecimal("100"), Instant.now()));
        return new ContextAssembler(portfolio, cash, provider(market), provider((NewsFeed) null), provider(h));
    }

    /** Flat bars with high − low = 2, so Wilder's ATR(14) is exactly 2. */
    private static List<DailyBar> flatBars(int count) {
        List<DailyBar> bars = new ArrayList<>();
        LocalDate day = LocalDate.of(2026, 10, 1);
        for (int i = 0; i < count; i++) {
            bars.add(new DailyBar(day.plusDays(i), new BigDecimal("100"), new BigDecimal("101"),
                    new BigDecimal("99"), new BigDecimal("100"), 1000));
        }
        return bars;
    }

    @Test
    void assemblerFillsAtrFromPriceHistory() {
        when(history.dailyBars(eq("AAPL"), anyInt())).thenReturn(flatBars(30));
        DecisionContext ctx = assembler(history).assemble("AAPL", "news", null);
        assertEquals(0, new BigDecimal("2").compareTo(ctx.atr()));
        assertTrue(lines(builder.userPrompt(ctx)).contains("ATR(14): 2.00 (2.0% of last)"));
    }

    @Test
    void failingHistoryMakesAtrNotAvailableWithoutFailingTheContext() {
        when(history.dailyBars(eq("AAPL"), anyInt()))
                .thenThrow(new SourceFailureException("alpaca/bars", "HTTP 500"));
        DecisionContext ctx = assembler(history).assemble("AAPL", "news", null);
        assertNull(ctx.atr());
        assertTrue(lines(builder.userPrompt(ctx)).contains("ATR(14): not available"));
    }

    @Test
    void tooFewBarsMakeAtrNotAvailable() {
        when(history.dailyBars(eq("AAPL"), anyInt())).thenReturn(flatBars(14));
        assertNull(assembler(history).assemble("AAPL", "news", null).atr());
    }

    @Test
    void noPriceHistoryBeanMakesAtrNotAvailable() {
        assertNull(assembler(null).assemble("AAPL", "news", null).atr());
    }

    @Test
    void oldAssemblerConstructorHasNoAtr() {
        when(portfolio.read()).thenReturn(new Portfolio(List.of(), BigDecimal.TEN, Map.of(), Instant.now()));
        when(cash.read()).thenReturn(List.of());
        ContextAssembler old = new ContextAssembler(portfolio, cash, provider((MarketDataFeed) null),
                provider((NewsFeed) null));
        assertNull(old.assemble("AAPL", "news", null).atr());
    }
}
