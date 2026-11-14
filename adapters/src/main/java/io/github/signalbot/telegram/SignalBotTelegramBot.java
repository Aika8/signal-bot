package io.github.signalbot.telegram;

import io.github.signalbot.application.account.AccountQueryService;
import io.github.signalbot.config.PortfolioProperties;
import io.github.signalbot.execution.ExecutionResult;
import io.github.signalbot.execution.ExecutionService;
import io.github.signalbot.execution.ExecutionState;
import io.github.signalbot.persistence.DailyPnlStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Long-polling Telegram bot: {@code /portfolio}, {@code /pnl}, and the execution
 * confirmation flow (Executed/Skipped buttons, then a fill-price reply).
 */
public class SignalBotTelegramBot extends TelegramLongPollingBot {

    private static final Logger LOG = LoggerFactory.getLogger(SignalBotTelegramBot.class);
    private static final ZoneId ALMATY = ZoneId.of("Asia/Almaty");

    private final String username;
    private final AccountQueryService accountQuery;
    private final PaperStateFormatter formatter;
    private final TelegramSender sender;
    private final DailyPnlStore dailyPnl;
    private final ExecutionService executionService;
    private final PortfolioProperties portfolio;
    /** chatId -> execution id awaiting a fill-price reply. */
    private final Map<String, Long> awaitingPrice = new ConcurrentHashMap<>();

    public SignalBotTelegramBot(String token, String username, AccountQueryService accountQuery,
                                PaperStateFormatter formatter, TelegramSender sender, DailyPnlStore dailyPnl,
                                ExecutionService executionService, PortfolioProperties portfolio) {
        super(token);
        this.username = username;
        this.accountQuery = accountQuery;
        this.formatter = formatter;
        this.sender = sender;
        this.dailyPnl = dailyPnl;
        this.executionService = executionService;
        this.portfolio = portfolio;
    }

    @Override
    public String getBotUsername() {
        return username;
    }

    @Override
    public void onUpdateReceived(Update update) {
        try {
            if (update.hasCallbackQuery()) {
                handleCallback(update.getCallbackQuery());
            } else if (update.hasMessage() && update.getMessage().hasText()) {
                handleText(update.getMessage().getChatId().toString(), update.getMessage().getText().trim());
            }
        } catch (Exception e) {
            LOG.warn("Failed to handle Telegram update: {}", e.getMessage());
        }
    }

    private void handleText(String chatId, String text) throws Exception {
        Long awaitingId = awaitingPrice.get(chatId);
        if (awaitingId != null) {
            resolveFillPrice(chatId, awaitingId, text);
            return;
        }
        switch (text) {
            case "/portfolio" -> sender.send(this, chatId,
                    "Source: " + portfolio.source() + "\n" + formatter.format(accountQuery.snapshot()));
            case "/pnl" -> sender.send(this, chatId,
                    formatter.formatPnl(accountQuery.snapshot(), dailyPnl.realizedOn(LocalDate.now(ALMATY))));
            default -> { /* ignore */ }
        }
    }

    private void resolveFillPrice(String chatId, long executionId, String text) throws Exception {
        if (text.equalsIgnoreCase("ok")) {
            ExecutionResult result = executionService.fillAtLastPrice(executionId);
            if (result.outcome() != ExecutionResult.Outcome.NO_PRICE) {
                awaitingPrice.remove(chatId);
            }
            sender.send(this, chatId, result.message());
            return;
        }
        try {
            BigDecimal price = new BigDecimal(text);
            if (price.signum() <= 0) {
                sender.send(this, chatId, "Price must be positive. Reply with a number, or 'ok' for last price.");
                return;
            }
            awaitingPrice.remove(chatId);
            sender.send(this, chatId, executionService.fill(executionId, price).message());
        } catch (NumberFormatException e) {
            sender.send(this, chatId, "Invalid price. Reply with a number, or 'ok' for last price.");
        }
    }

    private void handleCallback(CallbackQuery query) throws Exception {
        String chatId = query.getMessage().getChatId().toString();
        String data = query.getData();
        execute(AnswerCallbackQuery.builder().callbackQueryId(query.getId()).build());
        long executionId = Long.parseLong(data.substring(data.lastIndexOf(':') + 1));
        if (data.startsWith("exec:FILL:")) {
            ExecutionState state = executionService.state(executionId).orElse(null);
            if (state != ExecutionState.PENDING) {
                sender.send(this, chatId, state == null ? "Execution " + executionId + " not found."
                        : "Execution " + executionId + " is already " + state + "; nothing changed.");
                return;
            }
            awaitingPrice.put(chatId, executionId);
            sender.send(this, chatId, "Reply with the fill price, or 'ok' for last price.");
        } else if (data.startsWith("exec:SKIP:")) {
            awaitingPrice.remove(chatId, executionId);
            sender.send(this, chatId, executionService.skip(executionId, "skipped via Telegram").message());
        }
    }
}
