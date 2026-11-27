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
import io.github.signalbot.telegram.TelegramText;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** SB-039: the nightly review is pushed in Telegram-sized parts, one {@code sender.send} call per part. */
class NightlyReviewSplitTest {

    private static final String CHAT = "42";

    private final TradeQueryStore trades = mock(TradeQueryStore.class);
    private final DailyReviewStore reviews = mock(DailyReviewStore.class);
    private final ClaudeReviewer claude = mock(ClaudeReviewer.class);
    private final TelegramSender sender = mock(TelegramSender.class);
    private final SignalBotTelegramBot bot = mock(SignalBotTelegramBot.class);

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
        ExecutionStore executions = mock(ExecutionStore.class);
        when(executions.latenciesBetween(any(), any())).thenReturn(List.of());
        when(executions.slippageBetween(any(), any())).thenReturn(List.of());
        when(executions.countInStateBetween(any(ExecutionState.class), any(), any())).thenReturn(0);
        when(executions.feeTotalsBetween(any(), any())).thenReturn(new ExecutionStore.FeeTotals(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        when(trades.summariesBetween(any(), any())).thenReturn(List.of());
        when(trades.closedResultsR()).thenReturn(List.of());
        when(trades.closedWithoutRCount()).thenReturn(0L);
        when(botProvider.getIfAvailable()).thenReturn(bot);
        when(reviewerProvider.getIfAvailable()).thenReturn(claude);
        job = new NightlyReviewJob(portfolio, pnl, trades, executions, reviews, reviewerProvider, botProvider,
                sender, new TelegramProperties(true, "token", "bot", CHAT), new ObjectMapper());
    }

    private String storedReview() {
        ArgumentCaptor<String> review = ArgumentCaptor.forClass(String.class);
        verify(reviews).upsert(any(), review.capture(), anyString());
        return review.getValue();
    }

    @Test
    void longReviewIsSentInThreePartsThatJoinToTheWholeMessage() throws TelegramApiException {
        when(claude.review(anyString())).thenReturn("w".repeat(10_000));

        job.run();

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(sender, times(3)).send(eq(bot), eq(CHAT), text.capture());
        List<String> sent = text.getAllValues();
        sent.forEach(part -> assertTrue(part.length() <= TelegramText.MAX_LENGTH, "part of " + part.length()));
        assertEquals("Daily review\n" + storedReview(), String.join("", sent));
    }

    @Test
    void shortReviewIsOneSend() throws TelegramApiException {
        when(claude.review(anyString())).thenReturn("A quiet day.");

        job.run();

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(sender, times(1)).send(eq(bot), eq(CHAT), text.capture());
        assertEquals("Daily review\n" + storedReview(), text.getValue());
    }

    @Test
    void aFailedPartStopsThePushWithoutFailingTheJob() throws TelegramApiException {
        when(claude.review(anyString())).thenReturn("w".repeat(10_000));
        doThrow(new TelegramApiException("boom")).when(sender).send(any(), anyString(), anyString());

        job.run();

        verify(sender, times(1)).send(any(), anyString(), anyString());
        storedReview();
    }
}
