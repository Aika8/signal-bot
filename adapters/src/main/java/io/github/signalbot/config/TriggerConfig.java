package io.github.signalbot.config;

import io.github.signalbot.application.risk.RiskLimitEvaluator;
import io.github.signalbot.application.risk.RiskLimits;
import io.github.signalbot.application.trigger.Debouncer;
import io.github.signalbot.application.trigger.ExitGuard;
import io.github.signalbot.application.trigger.TriggerEvaluator;
import io.github.signalbot.application.trigger.TriggerSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/** Wires the framework-free trigger engine from configuration. */
@Configuration
public class TriggerConfig {

    @Bean
    public TriggerEvaluator triggerEvaluator(TriggerProperties props) {
        return new TriggerEvaluator(new TriggerSettings(
                props.priceMovePct(), Duration.ofMinutes(props.priceWindowMinutes())));
    }

    @Bean
    public Debouncer dispatchDebouncer(TriggerProperties props) {
        return new Debouncer(Duration.ofMinutes(props.debounceMinutes()));
    }

    /** One set of limits for the evaluator and the live path's sizing (D-003). */
    @Bean
    public RiskLimits riskLimits() {
        return RiskLimits.defaults();
    }

    @Bean
    public RiskLimitEvaluator riskLimitEvaluator(RiskLimits riskLimits) {
        return new RiskLimitEvaluator(riskLimits);
    }

    @Bean
    public ExitGuard exitGuard() {
        return new ExitGuard();
    }
}
