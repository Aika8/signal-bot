package io.github.signalbot.screen;

import io.github.signalbot.config.AlpacaProperties;
import io.github.signalbot.config.FinnhubProperties;
import io.github.signalbot.config.HttpClientConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

/**
 * Entry point for the one-off `screen` CLI. Deliberately NOT the worker runtime:
 * it scans only the screen package, imports just the HTTP clients, and excludes
 * the datasource/Flyway so it needs no database, scheduler, Telegram or paper wallet.
 */
@SpringBootApplication(
        scanBasePackageClasses = ScreenApplication.class,
        exclude = {DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
@Import(HttpClientConfig.class)
@EnableConfigurationProperties({AlpacaProperties.class, FinnhubProperties.class, ScreenProperties.class})
public class ScreenApplication {

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(new SpringApplicationBuilder(ScreenApplication.class)
                .web(WebApplicationType.NONE)
                .run(args)));
    }
}
