package io.github.signalbot.config;

import io.github.signalbot.domain.model.Ccy;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** Converts amounts to KZT using the configured FX rates. KZT is the base. */
@Component
public class FxConverter {

    private final FxProperties fx;

    public FxConverter(FxProperties fx) {
        this.fx = fx;
    }

    public BigDecimal toKzt(BigDecimal amount, Ccy currency) {
        if (amount == null) {
            return BigDecimal.ZERO;
        }
        return switch (currency) {
            case KZT -> amount;
            case USD -> amount.multiply(fx.usdKzt());
            case EUR -> amount.multiply(fx.eurKzt());
        };
    }

    public BigDecimal rateOf(Ccy currency) {
        return switch (currency) {
            case KZT -> BigDecimal.ONE;
            case USD -> fx.usdKzt();
            case EUR -> fx.eurKzt();
        };
    }
}
