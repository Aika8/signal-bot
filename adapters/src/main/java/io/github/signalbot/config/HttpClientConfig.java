package io.github.signalbot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** RestClient beans for the external REST APIs. */
@Configuration
public class HttpClientConfig {

    @Bean
    public RestClient alpacaDataRestClient(AlpacaProperties alpaca) {
        return RestClient.builder()
                .baseUrl(alpaca.dataUrl())
                .defaultHeader("APCA-API-KEY-ID", alpaca.keyId() == null ? "" : alpaca.keyId())
                .defaultHeader("APCA-API-SECRET-KEY", alpaca.apiSecret() == null ? "" : alpaca.apiSecret())
                .build();
    }

    @Bean
    public RestClient alpacaTradingRestClient(AlpacaProperties alpaca) {
        return RestClient.builder()
                .baseUrl(alpaca.tradingUrl())
                .defaultHeader("APCA-API-KEY-ID", alpaca.keyId() == null ? "" : alpaca.keyId())
                .defaultHeader("APCA-API-SECRET-KEY", alpaca.apiSecret() == null ? "" : alpaca.apiSecret())
                .build();
    }

    @Bean
    public RestClient finnhubRestClient(FinnhubProperties finnhub) {
        return RestClient.builder()
                .baseUrl(finnhub.baseUrl())
                .build();
    }
}
