package io.github.signalbot.telegram;

import io.github.signalbot.config.TelegramProperties;
import io.github.signalbot.domain.model.Action;
import io.github.signalbot.execution.ExecutionGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.math.BigDecimal;
import java.util.List;

/** Sends an execution prompt with Executed / Skipped inline buttons. */
@Component
@ConditionalOnProperty(prefix = "signalbot.telegram", name = "enabled", havingValue = "true")
public class TelegramExecutionGateway implements ExecutionGateway {

    private static final Logger LOG = LoggerFactory.getLogger(TelegramExecutionGateway.class);

    private final SignalBotTelegramBot bot;
    private final TelegramSender sender;
    private final TelegramProperties props;

    public TelegramExecutionGateway(SignalBotTelegramBot bot, TelegramSender sender, TelegramProperties props) {
        this.bot = bot;
        this.sender = sender;
        this.props = props;
    }

    @Override
    public void promptExecution(long executionId, String symbol, Action action, BigDecimal quantity, BigDecimal refPrice) {
        if (props.chatId() == null || props.chatId().isBlank()) {
            LOG.warn("No Telegram chat id; cannot prompt execution {}", executionId);
            return;
        }
        String text = action + " " + symbol + " x" + quantity + " (ref " + refPrice + ") — confirm?";
        InlineKeyboardButton executed = InlineKeyboardButton.builder()
                .text("Executed").callbackData("exec:FILL:" + executionId).build();
        InlineKeyboardButton skipped = InlineKeyboardButton.builder()
                .text("Skipped").callbackData("exec:SKIP:" + executionId).build();
        InlineKeyboardMarkup markup = InlineKeyboardMarkup.builder()
                .keyboard(List.of(List.of(executed, skipped)))
                .build();
        try {
            sender.sendMarkup(bot, props.chatId(), text, markup);
        } catch (Exception e) {
            LOG.warn("Failed to send execution prompt {}: {}", executionId, e.getMessage());
        }
    }
}
