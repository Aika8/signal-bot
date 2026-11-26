package io.github.signalbot.telegram;

import io.github.signalbot.config.TelegramProperties;
import io.github.signalbot.domain.model.Action;
import io.github.signalbot.execution.ExecutionGateway;
import io.github.signalbot.persistence.ExecutionStore;
import io.github.signalbot.persistence.ExecutionStore.ExecutionRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Sends an execution prompt with Executed / Skipped inline buttons. The stop and the sizing facts of a BUY
 * (D-002, D-003) are read from the execution row by id; the text is built by {@link ExecutionPromptFormatter}.
 */
@Component
@ConditionalOnProperty(prefix = "signalbot.telegram", name = "enabled", havingValue = "true")
public class TelegramExecutionGateway implements ExecutionGateway {

    private static final Logger LOG = LoggerFactory.getLogger(TelegramExecutionGateway.class);

    private final SignalBotTelegramBot bot;
    private final TelegramSender sender;
    private final TelegramProperties props;
    private final ExecutionStore store;

    public TelegramExecutionGateway(SignalBotTelegramBot bot, TelegramSender sender, TelegramProperties props,
                                    ExecutionStore store) {
        this.bot = bot;
        this.sender = sender;
        this.props = props;
        this.store = store;
    }

    @Override
    public void promptExecution(long executionId, String symbol, Action action, BigDecimal quantity, BigDecimal refPrice) {
        if (props.chatId() == null || props.chatId().isBlank()) {
            LOG.warn("No Telegram chat id; cannot prompt execution {}", executionId);
            return;
        }
        Optional<ExecutionRow> row = row(executionId);
        String text = ExecutionPromptFormatter.format(symbol, action, quantity, refPrice,
                row.map(ExecutionRow::stop).orElse(null), row.map(ExecutionRow::sizing).orElse(null),
                row.map(ExecutionRow::limitPrice).orElse(null));
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

    /** The stored row; if it cannot be read, the prompt still goes out, without the sizing lines. */
    private Optional<ExecutionRow> row(long executionId) {
        try {
            Optional<ExecutionRow> row = store.find(executionId);
            if (row.isEmpty()) {
                LOG.warn("Execution {} not found; prompting without sizing facts", executionId);
            }
            return row;
        } catch (RuntimeException e) {
            LOG.warn("Cannot read execution {}; prompting without sizing facts: {}", executionId, e.getMessage());
            return Optional.empty();
        }
    }
}
