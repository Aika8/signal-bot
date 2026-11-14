package io.github.signalbot.decision;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.signalbot.config.ClaudeProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/** Thin, resilient wrapper over one Claude completion. Loaded only when the engine is enabled. */
@Component
@ConditionalOnProperty(prefix = "signalbot.claude", name = "enabled", havingValue = "true")
public class ClaudeClient {

    private final AnthropicClient client;
    private final ClaudeProperties props;

    public ClaudeClient(AnthropicClient client, ClaudeProperties props) {
        this.client = client;
        this.props = props;
    }

    @Retry(name = "claude")
    @CircuitBreaker(name = "claude")
    public String complete(String system, String user) {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(props.model())
                .maxTokens(props.maxTokens())
                .system(system)
                .addUserMessage(user)
                .build();
        Message response = client.messages().create(params);
        return response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(TextBlock::text)
                .collect(Collectors.joining("\n"));
    }
}
