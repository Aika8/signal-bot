package io.github.signalbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Telegram bot settings. {@code enabled} gates the bot and verdict push. */
@ConfigurationProperties(prefix = "signalbot.telegram")
public record TelegramProperties(boolean enabled, String token, String username, String chatId) {
}
