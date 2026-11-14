package io.github.signalbot.telegram;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.bots.AbsSender;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

/** Sends Telegram messages (plain or with an inline keyboard), guarded by retry + circuit breaker. */
@Component
public class TelegramSender {

    @Retry(name = "telegram")
    @CircuitBreaker(name = "telegram")
    public void send(AbsSender bot, String chatId, String text) throws TelegramApiException {
        bot.execute(SendMessage.builder().chatId(chatId).text(text).build());
    }

    @Retry(name = "telegram")
    @CircuitBreaker(name = "telegram")
    public void sendMarkup(AbsSender bot, String chatId, String text, InlineKeyboardMarkup markup)
            throws TelegramApiException {
        bot.execute(SendMessage.builder().chatId(chatId).text(text).replyMarkup(markup).build());
    }
}
