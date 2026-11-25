package io.github.signalbot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The worker. Same as {@code @SpringBootApplication}, except that the {@code screen} CLI package is not scanned:
 * {@code ScreenApplication} excludes the datasource and Flyway (Boot merges excludes of every scanned
 * {@code @EnableAutoConfiguration}, so the worker lost its {@code JdbcClient}) and {@code ScreenRunner} would run the
 * screener at worker startup. The CLI has its own entry point ({@code ./gradlew screen}).
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(excludeFilters = {
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class),
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = "io\\.github\\.signalbot\\.screen\\..*")})
@ConfigurationPropertiesScan
@EnableScheduling
public class SignalBotApplication {

    public static void main(String[] args) {
        SpringApplication.run(SignalBotApplication.class, args);
    }
}
