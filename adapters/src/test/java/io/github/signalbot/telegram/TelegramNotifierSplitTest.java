package io.github.signalbot.telegram;

import io.github.signalbot.config.TelegramProperties;
import io.github.signalbot.domain.model.Action;
import io.github.signalbot.domain.model.Verdict;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** AUTO-20261127-4: a verdict longer than Telegram's limit is pushed in parts, one {@code sender.send} per part. */
class TelegramNotifierSplitTest {

    private static final String CHAT = "42";

    private final TelegramSender sender = mock(TelegramSender.class);
    private final SignalBotTelegramBot bot = mock(SignalBotTelegramBot.class);
    private final TelegramNotifier notifier =
            new TelegramNotifier(bot, sender, new TelegramProperties(true, "token", "bot", CHAT));

    private static Verdict verdict(String rationale) {
        return new Verdict("AAPL", Action.BUY, new BigDecimal("3"), new BigDecimal("190.50"),
                new BigDecimal("185.00"), null, 0.7, rationale, Instant.EPOCH);
    }

    private List<String> sentTexts(int calls) throws TelegramApiException {
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(sender, times(calls)).send(eq(bot), eq(CHAT), text.capture());
        return text.getAllValues();
    }

    @Test
    void longVerdictIsSentInPartsThatJoinToTheWholeText() throws TelegramApiException {
        String rationale = "r".repeat(6_000);
        String context = "c".repeat(3_000);

        notifier.send(verdict(rationale), context);

        List<String> sent = sentTexts(3);
        sent.forEach(part -> assertTrue(part.length() <= TelegramText.MAX_LENGTH, "part of " + part.length()));
        String joined = String.join("", sent);
        assertTrue(joined.startsWith("BUY AAPL x3 @ 190.50\nconfidence: 0.7\n"), joined.substring(0, 40));
        assertTrue(joined.endsWith("\n" + context));
        assertEquals("BUY AAPL x3 @ 190.50\nconfidence: 0.7\n" + rationale + "\n" + context, joined);
    }

    @Test
    void shortVerdictIsOneSend() throws TelegramApiException {
        notifier.send(verdict("Breakout above resistance."), "ATR 2.1");

        assertEquals(List.of("BUY AAPL x3 @ 190.50\nconfidence: 0.7\nBreakout above resistance.\nATR 2.1"),
                sentTexts(1));
    }

    @Test
    void aFailedPartStopsThePushWithoutThrowing() throws TelegramApiException {
        doThrow(new TelegramApiException("boom")).when(sender).send(any(), anyString(), anyString());

        notifier.send(verdict("r".repeat(10_000)), null);

        verify(sender, times(1)).send(any(), anyString(), anyString());
    }
}
