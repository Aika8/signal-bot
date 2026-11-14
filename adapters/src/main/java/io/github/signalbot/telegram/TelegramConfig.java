package io.github.signalbot.telegram;

import io.github.signalbot.application.account.AccountQueryService;
import io.github.signalbot.config.PortfolioProperties;
import io.github.signalbot.config.TelegramProperties;
import io.github.signalbot.execution.ExecutionService;
import io.github.signalbot.persistence.DailyPnlStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

/** Creates and registers the Telegram bot only when {@code signalbot.telegram.enabled=true}. */
@Configuration
@ConditionalOnProperty(prefix = "signalbot.telegram", name = "enabled", havingValue = "true")
public class TelegramConfig {

    private static final Logger LOG = LoggerFactory.getLogger(TelegramConfig.class);

    @Bean
    public SignalBotTelegramBot signalBotTelegramBot(TelegramProperties props, AccountQueryService accountQuery,
                                                     PaperStateFormatter formatter, TelegramSender sender,
                                                     DailyPnlStore dailyPnl, ExecutionService executionService,
                                                     PortfolioProperties portfolio) throws TelegramApiException {
        SignalBotTelegramBot bot = new SignalBotTelegramBot(
                props.token(), props.username(), accountQuery, formatter, sender, dailyPnl, executionService, portfolio);
        new TelegramBotsApi(DefaultBotSession.class).registerBot(bot);
        LOG.info("Telegram bot '{}' registered (long polling).", props.username());
        return bot;
    }
}
