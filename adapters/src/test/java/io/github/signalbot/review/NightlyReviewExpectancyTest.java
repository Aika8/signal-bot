package io.github.signalbot.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.signalbot.config.TelegramProperties;
import io.github.signalbot.domain.model.Portfolio;
import io.github.signalbot.domain.port.PortfolioReader;
import io.github.signalbot.execution.ExecutionState;
import io.github.signalbot.persistence.DailyPnlStore;
import io.github.signalbot.persistence.DailyReviewStore;
import io.github.signalbot.persistence.ExecutionStore;
import io.github.signalbot.persistence.TradeQueryStore;
import io.github.signalbot.telegram.SignalBotTelegramBot;
import io.github.signalbot.telegram.TelegramSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** SB-016: the nightly review's "Trades in R" section, computed by code; no Claude, Telegram or database call. */
class NightlyReviewExpectancyTest {

    private static final String EXPECTED_LINE =
            "n=4, win rate 50 %, avg win 1.75R, avg loss 1.00R, E = +0.38R";

    private final TradeQueryStore trades = mock(TradeQueryStore.class);
    private final ExecutionStore executions = mock(ExecutionStore.class);
    private final DailyReviewStore reviews = mock(DailyReviewStore.class);
    private final ClaudeReviewer claude = mock(ClaudeReviewer.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<ClaudeReviewer> reviewerProvider = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<SignalBotTelegramBot> botProvider = mock(ObjectProvider.class);

    private NightlyReviewJob job;

    @BeforeEach
    void setUp() {
        PortfolioReader portfolio = () -> new Portfolio(List.of(), new BigDecimal("1000000"), Map.of(), Instant.EPOCH);
        DailyPnlStore pnl = mock(DailyPnlStore.class);
        when(pnl.realizedOn(any())).thenReturn(BigDecimal.ZERO);
        when(executions.latenciesBetween(any(), any())).thenReturn(List.of());
        when(executions.slippageBetween(any(), any())).thenReturn(List.of());
        when(executions.countInStateBetween(any(ExecutionState.class), any(), any())).thenReturn(0);
        when(executions.feeTotalsBetween(any(), any())).thenReturn(new ExecutionStore.FeeTotals(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        when(trades.summariesBetween(any(), any())).thenReturn(List.of());
        when(trades.closedResultsR()).thenReturn(List.of(
                new BigDecimal("2.0000"), new BigDecimal("1.5000"), new BigDecimal("-1.0000"),
                new BigDecimal("-1.0000")));
        when(trades.closedWithoutRCount()).thenReturn(0L);
        when(botProvider.getIfAvailable()).thenReturn(null);
        job = new NightlyReviewJob(portfolio, pnl, trades, executions, reviews, reviewerProvider, botProvider,
                mock(TelegramSender.class), new TelegramProperties(false, null, null, null), new ObjectMapper());
    }

    private String storedReview() {
        ArgumentCaptor<String> review = ArgumentCaptor.forClass(String.class);
        verify(reviews).upsert(any(), review.capture(), anyString());
        return review.getValue();
    }

    @Test
    void withoutClaudeTheReviewShowsTradesInR() {
        when(reviewerProvider.getIfAvailable()).thenReturn(null);

        job.run();

        String review = storedReview();
        assertTrue(review.contains("Trades in R"), review);
        assertTrue(review.contains(EXPECTED_LINE), review);
        assertTrue(review.contains("too few trades to judge"), review);
        assertFalse(review.contains("left out"), review);
    }

    @Test
    void claudeReceivesTheNumbersAndTheReviewKeepsThemVerbatim() {
        when(reviewerProvider.getIfAvailable()).thenReturn(claude);
        when(claude.review(anyString())).thenReturn("A quiet day.");

        job.run();

        ArgumentCaptor<String> input = ArgumentCaptor.forClass(String.class);
        verify(claude).review(input.capture());
        assertTrue(input.getValue().contains(EXPECTED_LINE), input.getValue());
        String review = storedReview();
        assertTrue(review.startsWith("A quiet day."), review);
        assertTrue(review.contains(EXPECTED_LINE), review);
    }

    @Test
    void twentyTradesAreEnoughToJudge() {
        when(reviewerProvider.getIfAvailable()).thenReturn(null);
        when(trades.closedResultsR()).thenReturn(Collections.nCopies(20, new BigDecimal("0.5000")));
        when(trades.closedWithoutRCount()).thenReturn(2L);

        job.run();

        String review = storedReview();
        assertTrue(review.contains("n=20, win rate 100 %, avg win 0.50R, avg loss 0.00R, E = +0.50R"), review);
        assertFalse(review.contains("too few trades to judge"), review);
        assertTrue(review.contains("left out (no 1R): 2"), review);
    }

    @Test
    void negativeExpectancyHasNoPlusSign() {
        when(reviewerProvider.getIfAvailable()).thenReturn(null);
        when(trades.closedResultsR()).thenReturn(List.of(new BigDecimal("-1.0000"), new BigDecimal("0.5000")));

        job.run();

        String review = storedReview();
        assertTrue(review.contains("E = -0.25R"), review);
    }
}
