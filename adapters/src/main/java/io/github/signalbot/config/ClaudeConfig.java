package io.github.signalbot.config;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the Anthropic client only when the Claude engine is enabled. The SDK
 * reads its credential from the environment (ANTHROPIC_API_KEY) via fromEnv().
 */
@Configuration
@ConditionalOnProperty(prefix = "signalbot.claude", name = "enabled", havingValue = "true")
public class ClaudeConfig {

    @Bean
    public AnthropicClient anthropicClient() {
        return AnthropicOkHttpClient.fromEnv();
    }
}
