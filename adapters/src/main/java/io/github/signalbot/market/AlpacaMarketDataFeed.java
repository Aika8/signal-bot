package io.github.signalbot.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.signalbot.config.AlpacaProperties;
import io.github.signalbot.domain.model.PriceSnapshot;
import io.github.signalbot.domain.model.PriceTick;
import io.github.signalbot.domain.port.MarketDataFeed;
import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.Session;
import org.glassfish.tyrus.client.ClientManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Alpaca market data: REST snapshot + IEX trade stream (Jakarta WebSocket).
 * Loaded only when {@code signalbot.alpaca.enabled=true}. Message decoding is
 * delegated to {@link AlpacaStreamDecoder} (unit-tested); this class owns only
 * the HTTP/WS plumbing.
 */
@Component
@ConditionalOnProperty(prefix = "signalbot.alpaca", name = "enabled", havingValue = "true")
public class AlpacaMarketDataFeed implements MarketDataFeed {

    private static final Logger LOG = LoggerFactory.getLogger(AlpacaMarketDataFeed.class);

    private final RestClient data;
    private final AlpacaProperties props;
    private final AlpacaSnapshotMapper snapshotMapper;
    private final AlpacaStreamDecoder decoder;
    private final ObjectMapper objectMapper;

    public AlpacaMarketDataFeed(RestClient alpacaDataRestClient, AlpacaProperties props,
                                AlpacaSnapshotMapper snapshotMapper, AlpacaStreamDecoder decoder,
                                ObjectMapper objectMapper) {
        this.data = alpacaDataRestClient;
        this.props = props;
        this.snapshotMapper = snapshotMapper;
        this.decoder = decoder;
        this.objectMapper = objectMapper;
    }

    @Override
    @Retry(name = "alpaca")
    @CircuitBreaker(name = "alpaca")
    public PriceSnapshot snapshot(String symbol) {
        JsonNode body = data.get()
                .uri("/v2/stocks/{symbol}/snapshot", symbol)
                .retrieve()
                .body(JsonNode.class);
        return snapshotMapper.toSnapshot(symbol, body);
    }

    @Override
    public AutoCloseable subscribe(Collection<String> symbols, Consumer<PriceTick> onTick) {
        try {
            Endpoint endpoint = new Endpoint() {
                @Override
                public void onOpen(Session session, EndpointConfig config) {
                    session.addMessageHandler(String.class, message -> dispatch(message, onTick));
                    send(session, Map.of("action", "auth", "key", props.keyId(), "secret", props.apiSecret()));
                    send(session, Map.of("action", "subscribe", "trades", List.copyOf(symbols)));
                }
            };
            Session session = ClientManager.createClient().connectToServer(
                    endpoint, ClientEndpointConfig.Builder.create().build(), URI.create(props.streamUrl()));
            LOG.info("Alpaca stream subscribed to {} symbols", symbols.size());
            return session::close;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to open Alpaca market-data stream", e);
        }
    }

    private void dispatch(String message, Consumer<PriceTick> onTick) {
        try {
            decoder.decode(objectMapper.readTree(message)).forEach(onTick);
        } catch (Exception e) {
            LOG.warn("Failed to decode Alpaca stream frame: {}", e.getMessage());
        }
    }

    private void send(Session session, Map<String, Object> payload) {
        try {
            session.getAsyncRemote().sendText(objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            LOG.warn("Failed to send Alpaca stream command: {}", e.getMessage());
        }
    }
}
