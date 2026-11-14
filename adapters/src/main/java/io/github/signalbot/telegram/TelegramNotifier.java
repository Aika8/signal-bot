package io.github.signalbot.telegram;

import io.github.signalbot.config.TelegramProperties;
import io.github.signalbot.domain.model.Verdict;
import io.github.signalbot.domain.port.Notifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

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
        try {
            sender.send(bot, props.chatId(), format(verdict, contextSummary));
        } catch (Exception e) {
            LOG.warn("Failed to push verdict for {}: {}", verdict.symbol(), e.getMessage());
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
