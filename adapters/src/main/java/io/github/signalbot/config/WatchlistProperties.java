package io.github.signalbot.config;

import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/** Config-only watchlist, capped at 10 symbols. */
@Validated
@ConfigurationProperties(prefix = "signalbot.watchlist")
public record WatchlistProperties(
        @Size(max = 10, message = "watchlist is limited to 10 symbols") List<String> symbols) {

    public WatchlistProperties {
        symbols = symbols == null ? List.of() : List.copyOf(symbols);
    }
}
