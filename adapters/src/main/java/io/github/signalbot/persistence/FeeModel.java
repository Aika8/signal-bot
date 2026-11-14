package io.github.signalbot.persistence;

import io.github.signalbot.config.FxConverter;
import io.github.signalbot.config.PaperWalletProperties;
import io.github.signalbot.domain.model.Ccy;
import io.github.signalbot.domain.model.FeeBreakdown;
import io.github.signalbot.domain.model.Venue;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Venue-aware fee model. US: {@code feePerShareUsd * qty + feePerOrderUsd}, converted
 * to KZT. Non-US: {@code feeRate} of notional (the 0.1% fallback). Pure given its config.
 */
@Component
public class FeeModel {

    private final FxConverter fx;
    private final PaperWalletProperties props;

    public FeeModel(FxConverter fx, PaperWalletProperties props) {
        this.fx = fx;
        this.props = props;
    }

    public FeeBreakdown compute(Venue venue, BigDecimal quantity, BigDecimal notionalKzt) {
        if (venue == Venue.US) {
            BigDecimal perShare = kzt(fx.toKzt(props.feePerShareUsd().multiply(quantity), Ccy.USD));
            BigDecimal perOrder = kzt(fx.toKzt(props.feePerOrderUsd(), Ccy.USD));
            return new FeeBreakdown(perShare, perOrder, BigDecimal.ZERO.setScale(4),
                    perShare.add(perOrder), "us-per-share");
        }
        BigDecimal pct = kzt(notionalKzt.multiply(props.feeRate()));
        return new FeeBreakdown(BigDecimal.ZERO.setScale(4), BigDecimal.ZERO.setScale(4), pct, pct, "percentage");
    }

    private static BigDecimal kzt(BigDecimal amount) {
        return amount.setScale(4, RoundingMode.HALF_UP);
    }
}
