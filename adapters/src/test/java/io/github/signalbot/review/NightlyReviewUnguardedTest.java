package io.github.signalbot.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.signalbot.config.TelegramProperties;
import io.github.signalbot.domain.model.Ccy;
import io.github.signalbot.domain.model.Portfolio;
import io.github.signalbot.domain.model.Position;
import io.github.signalbot.domain.model.Venue;
import io.github.signalbot.domain.port.PortfolioReader;
import io.github.signalbot.execution.ExecutionState;
import io.github.signalbot.persistence.DailyPnlStore;
import io.github.signalbot.persistence.DailyReviewStore;
import io.github.signalbot.persistence.ExecutionStore;
import io.github.signalbot.persistence.PositionGuardStore;
import io.github.signalbot.persistence.PositionGuardStore.StoredGuard;
import io.github.signalbot.persistence.TradeQueryStore;
import io.github.signalbot.telegram.SignalBotTelegramBot;
import io.github.signalbot.telegram.TelegramSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** SB-035: the nightly review lists held positions that have no stored guard (no stop). */
class NightlyReviewUnguardedTest {

    private final List<Position> positions = new ArrayList<>();
    private final TradeQueryStore trades = mock(TradeQueryStore.class);
    private final ExecutionStore executions = mock(ExecutionStore.class);
    private final DailyReviewStore reviews = mock(DailyReviewStore.class);
    private final PositionGuardStore guards = mock(PositionGuardStore.class);
    private final ClaudeReviewer claude = mock(ClaudeReviewer.class);
    private final DailyPnlStore pnl = mock(DailyPnlStore.class);
    private final PortfolioReader portfolio =
            () -> new Portfolio(positions, new BigDecimal("1000000"), Map.of(), Instant.EPOCH);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<ClaudeReviewer> reviewerProvider = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<SignalBotTelegramBot> botProvider = mock(ObjectProvider.class);

    @BeforeEach
    void setUp() {
        when(pnl.realizedOn(any())).thenReturn(BigDecimal.ZERO);
        when(executions.latenciesBetween(any(), any())).thenReturn(List.of());
        when(executions.slippageBetween(any(), any())).thenReturn(List.of());
        when(executions.countInStateBetween(any(ExecutionState.class), any(), any())).thenReturn(0);
        when(executions.feeTotalsBetween(any(), any())).thenReturn(new ExecutionStore.FeeTotals(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        when(trades.summariesBetween(any(), any())).thenReturn(List.of());
        when(trades.closedResultsR()).thenReturn(List.of());
        when(trades.closedWithoutRCount()).thenReturn(0L);
        when(botProvider.getIfAvailable()).thenReturn(null);
        when(reviewerProvider.getIfAvailable()).thenReturn(null);
    }

    private NightlyReviewJob job(PositionGuardStore store) {
        TelegramProperties telegram = new TelegramProperties(false, null, null, null);
        return store == null
                ? new NightlyReviewJob(portfolio, pnl, trades, executions, reviews, reviewerProvider, botProvider,
                        mock(TelegramSender.class), telegram, new ObjectMapper())
                : new NightlyReviewJob(portfolio, pnl, trades, executions, reviews, reviewerProvider, botProvider,
                        mock(TelegramSender.class), telegram, new ObjectMapper(), store);
    }

    private static Position held(String symbol, String qty) {
        BigDecimal q = new BigDecimal(qty);
        return new Position(symbol, null, Venue.US, q, q, new BigDecimal("100"), new BigDecimal("100"), Ccy.USD,
                new BigDecimal("50000"), BigDecimal.ZERO, BigDecimal.ZERO, false, false, Instant.EPOCH);
    }

    private static StoredGuard guard(String symbol) {
        return new StoredGuard(symbol, new BigDecimal("90"), new BigDecimal("120"), null, null, false, null);
    }

    private String storedReview() {
        ArgumentCaptor<String> review = ArgumentCaptor.forClass(String.class);
        verify(reviews).upsert(any(), review.capture(), anyString());
        return review.getValue();
    }

    @Test
    void aHeldPositionWithoutAGuardIsListed() {
        positions.add(held("XYZ", "10"));
        positions.add(held("AAPL", "3"));
        when(guards.findAllGuards()).thenReturn(List.of(guard("AAPL")));

        job(guards).run();

        String review = storedReview();
        assertTrue(review.contains("Held without a stop: XYZ (qty 10)\n"), review);
    }

    @Test
    void quantityIsPrintedWithoutTrailingZeros() {
        positions.add(held("XYZ", "10.0000"));
        when(guards.findAllGuards()).thenReturn(List.of());

        job(guards).run();

        String review = storedReview();
        assertTrue(review.contains("Held without a stop: XYZ (qty 10)\n"), review);
    }

    @Test
    void everyHeldPositionGuardedSaysNone() {
        positions.add(held("AAPL", "3"));
        when(guards.findAllGuards()).thenReturn(List.of(guard("AAPL")));

        job(guards).run();

        assertTrue(storedReview().contains("Held without a stop: none\n"), storedReview());
    }

    @Test
    void noPositionsSaysNone() {
        when(guards.findAllGuards()).thenReturn(List.of(guard("OLD")));

        job(guards).run();

        assertTrue(storedReview().contains("Held without a stop: none\n"), storedReview());
    }

    @Test
    void theOldConstructorSaysNotChecked() {
        positions.add(held("XYZ", "10"));

        job(null).run();

        assertTrue(storedReview().contains("Held without a stop: not checked\n"), storedReview());
    }

    @Test
    void anUnreadableGuardStoreSaysNotCheckedAndTheReviewIsStillStored() {
        positions.add(held("XYZ", "10"));
        when(guards.findAllGuards()).thenThrow(new IllegalStateException("db down"));

        job(guards).run();

        String review = storedReview();
        assertTrue(review.contains("Held without a stop: not checked (guards unreadable)\n"), review);
        assertTrue(!review.contains("XYZ (qty"), review);
    }

    @Test
    void claudeReceivesTheLineAndTheReviewKeepsItVerbatim() {
        positions.add(held("XYZ", "10"));
        when(guards.findAllGuards()).thenReturn(List.of());
        when(reviewerProvider.getIfAvailable()).thenReturn(claude);
        when(claude.review(anyString())).thenReturn("A quiet day.");

        job(guards).run();

        ArgumentCaptor<String> input = ArgumentCaptor.forClass(String.class);
        verify(claude).review(input.capture());
        assertTrue(input.getValue().contains("Held without a stop: XYZ (qty 10)\n"), input.getValue());
        String review = storedReview();
        assertTrue(review.startsWith("A quiet day."), review);
        assertTrue(review.contains("Held without a stop: XYZ (qty 10)\n"), review);
    }
}
