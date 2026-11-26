package io.github.signalbot.telegram;

import io.github.signalbot.application.risk.StopSource;
import io.github.signalbot.config.TelegramProperties;
import io.github.signalbot.domain.model.Action;
import io.github.signalbot.execution.ExecutionSource;
import io.github.signalbot.execution.ExecutionState;
import io.github.signalbot.execution.SizingInfo;
import io.github.signalbot.persistence.ExecutionStore;
import io.github.signalbot.persistence.ExecutionStore.ExecutionRow;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** SB-010: the execution prompt shows the stop, its source and 1R for a BUY; nothing extra for a SELL or exit. */
class ExecutionPromptFormatterTest {

    private static final String SKIP_LINE = "Skipped = do not take the trade";

    private static SizingInfo atrSizing() {
        return new SizingInfo(StopSource.ATR, new BigDecimal("185.00"), new BigDecimal("3.70"),
                new BigDecimal("7.6"), new BigDecimal("12500"), new BigDecimal("10"));
    }

    @Test
    void buyWithReplacedStopShowsAtrSourceClaudesStopAtrAndOneR() {
        String text = ExecutionPromptFormatter.format("AAPL", Action.BUY, new BigDecimal("7"),
                new BigDecimal("190.00"), new BigDecimal("182.40"), atrSizing());

        assertEquals("""
                BUY AAPL x7 (ref 190.00) — confirm?
                Stop 182.40 (ATR; Claude: 185.00)
                ATR(14) 3.70
                1R = 12 500 ₸ (qty 7)
                Skipped = do not take the trade""", text);
    }

    @Test
    void buyWithClaudesStopShowsClaudeSourceWithoutAReplacedValue() {
        SizingInfo sizing = new SizingInfo(StopSource.CLAUDE, new BigDecimal("183"), new BigDecimal("3.7"),
                new BigDecimal("7"), new BigDecimal("4321.6"), null);

        String text = ExecutionPromptFormatter.format("AAPL", Action.BUY, new BigDecimal("3"),
                new BigDecimal("190"), new BigDecimal("183"), sizing);

        assertEquals("""
                BUY AAPL x3 (ref 190) — confirm?
                Stop 183.00 (CLAUDE)
                ATR(14) 3.70
                1R = 4 322 ₸ (qty 3)
                Skipped = do not take the trade""", text);
    }

    @Test
    void atrStopWhenClaudeGaveNoneSaysSo() {
        SizingInfo sizing = new SizingInfo(StopSource.ATR, null, new BigDecimal("3.70"), new BigDecimal("7.4"),
                new BigDecimal("1250000"), null);

        String text = ExecutionPromptFormatter.format("MSFT", Action.BUY, new BigDecimal("5"),
                new BigDecimal("400"), new BigDecimal("392.6037"), sizing);

        assertTrue(text.contains("\nStop 392.6037 (ATR; Claude: none)\n"), text);
        assertTrue(text.contains("\n1R = 1 250 000 ₸ (qty 5)\n"), text);
        assertTrue(text.endsWith(SKIP_LINE), text);
    }

    @Test
    void sellHasNoSizingLinesEvenWithSizingFacts() {
        String text = ExecutionPromptFormatter.format("AAPL", Action.SELL, new BigDecimal("7"),
                new BigDecimal("190.00"), new BigDecimal("182.40"), atrSizing());

        assertEquals("SELL AAPL x7 (ref 190.00) — confirm?", text);
    }

    @Test
    void exitWithoutSizingKeepsTheOldPrompt() {
        String text = ExecutionPromptFormatter.format("AAPL", Action.SELL, new BigDecimal("7"),
                new BigDecimal("181.00"), null, null);

        assertEquals("SELL AAPL x7 (ref 181.00) — confirm?", text);
    }

    @Test
    void buyWithoutSizingKeepsTheOldPrompt() {
        String text = ExecutionPromptFormatter.format("AAPL", Action.BUY, new BigDecimal("7"),
                new BigDecimal("190.00"), new BigDecimal("182.40"), null);

        assertEquals("BUY AAPL x7 (ref 190.00) — confirm?", text);
        assertFalse(text.contains("Stop"));
    }

    // --- the gateway reads the row by id and sends the formatted text with the unchanged buttons ---

    private final SignalBotTelegramBot bot = mock(SignalBotTelegramBot.class);
    private final TelegramSender sender = mock(TelegramSender.class);
    private final ExecutionStore store = mock(ExecutionStore.class);
    private final TelegramProperties props = new TelegramProperties(true, "token", "bot", "42");

    private String sentText(long id, Action action) throws Exception {
        new TelegramExecutionGateway(bot, sender, props, store)
                .promptExecution(id, "AAPL", action, new BigDecimal("7"), new BigDecimal("190.00"));
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<InlineKeyboardMarkup> markup = ArgumentCaptor.forClass(InlineKeyboardMarkup.class);
        verify(sender).sendMarkup(eq(bot), eq("42"), text.capture(), markup.capture());
        List<InlineKeyboardButton> row = markup.getValue().getKeyboard().get(0);
        assertEquals("Executed", row.get(0).getText());
        assertEquals("exec:FILL:" + id, row.get(0).getCallbackData());
        assertEquals("Skipped", row.get(1).getText());
        assertEquals("exec:SKIP:" + id, row.get(1).getCallbackData());
        return text.getValue();
    }

    @Test
    void gatewayShowsTheStoredStopAndSizingOfABuy() throws Exception {
        when(store.find(11L)).thenReturn(Optional.of(new ExecutionRow(11L, "AAPL", Action.BUY,
                ExecutionSource.VERDICT, new BigDecimal("7"), new BigDecimal("182.40"), null,
                new BigDecimal("190.00"), ExecutionState.PENDING, Instant.now(), atrSizing())));

        String text = sentText(11L, Action.BUY);

        assertTrue(text.startsWith("BUY AAPL x7 (ref 190.00) — confirm?\nStop 182.40 (ATR; Claude: 185.00)\n"),
                text);
        assertTrue(text.contains("\n1R = 12 500 ₸ (qty 7)\n"), text);
    }

    @Test
    void gatewayExitPromptHasNoSizingLines() throws Exception {
        when(store.find(12L)).thenReturn(Optional.of(new ExecutionRow(12L, "AAPL", Action.SELL,
                ExecutionSource.EXIT, new BigDecimal("7"), null, null, new BigDecimal("190.00"),
                ExecutionState.PENDING, Instant.now())));

        assertEquals("SELL AAPL x7 (ref 190.00) — confirm?", sentText(12L, Action.SELL));
    }

    @Test
    void gatewayStillPromptsWhenTheRowCannotBeRead() throws Exception {
        when(store.find(13L)).thenThrow(new IllegalStateException("db down"));

        assertEquals("BUY AAPL x7 (ref 190.00) — confirm?", sentText(13L, Action.BUY));
    }

    @Test
    void gatewayWithoutChatIdSendsNothing() throws Exception {
        new TelegramExecutionGateway(bot, sender, new TelegramProperties(true, "t", "b", " "), store)
                .promptExecution(1L, "AAPL", Action.BUY, BigDecimal.ONE, BigDecimal.TEN);

        verify(sender, org.mockito.Mockito.never()).sendMarkup(any(), anyString(), anyString(), any());
    }
}
