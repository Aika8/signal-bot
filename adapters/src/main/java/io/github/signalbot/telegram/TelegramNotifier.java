package io.github.signalbot.telegram;

import io.github.signalbot.config.TelegramProperties;
import io.github.signalbot.domain.model.Verdict;
import io.github.signalbot.domain.port.Notifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/** Pushes verdicts to the configured Telegram chat. Loaded only when the bot is enabled. */
@Component
@ConditionalOnProperty(prefix = "signalbot.telegram", name = "enabled", havingValue = "true")
public class TelegramNotifier implements Notifier {

    private static final Logger LOG = LoggerFactory.getLogger(TelegramNotifier.class);

    private final SignalBotTelegramBot bot;
    private final TelegramSender sender;
    private final TelegramProperties props;

    public TelegramNotifier(SignalBotTelegramBot bot, TelegramSender sender, TelegramProperties props) {
        this.bot = bot;
        this.sender = sender;
        this.props = props;
    }

    @Override
    public void send(Verdict verdict, String contextSummary) {
        if (props.chatId() == null || props.chatId().isBlank()) {
            LOG.warn("No Telegram chat id configured; cannot push verdict for {}", verdict.symbol());
            return;
        }
        // Telegram refuses a message over its limit, so a long verdict goes out in parts, one call per part
        // (AUTO-20261127-4). A failed part stops the push, as NightlyReviewJob does: later parts out of context help
        // nobody, and the retry inside TelegramSender never resends parts already delivered.
        List<String> parts = TelegramText.split(format(verdict, contextSummary));
        for (int i = 0; i < parts.size(); i++) {
            try {
                sender.send(bot, props.chatId(), parts.get(i));
            } catch (Exception e) {
                LOG.warn("Failed to push verdict for {} (part {}/{}): {}; the remaining parts are not sent",
                        verdict.symbol(), i + 1, parts.size(), e.getMessage());
                return;
            }
        }
    }

    @Override
    public void alert(String symbol, String message) {
        if (props.chatId() == null || props.chatId().isBlank()) {
            LOG.warn("No Telegram chat id configured; cannot push alert for {}: {}", symbol, message);
            return;
        }
        try {
            sender.send(bot, props.chatId(), "\u26a0\ufe0f " + symbol + " \u2014 " + message);
        } catch (Exception e) {
            LOG.warn("Failed to push alert for {}: {}", symbol, e.getMessage());
        }
    }

    private static String format(Verdict v, String contextSummary) {
        return new StringBuilder()
                .append(v.action()).append(' ').append(v.symbol())
                .append(" x").append(v.quantity())
                .append(v.entry() == null ? "" : " @ " + v.entry())
                .append("\nconfidence: ").append(v.confidence())
                .append("\n").append(v.rationale())
                .append(contextSummary == null || contextSummary.isBlank() ? "" : "\n" + contextSummary)
                .toString();
    }
}
