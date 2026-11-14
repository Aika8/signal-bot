package io.github.signalbot.persistence;

import io.github.signalbot.config.FxConverter;
import io.github.signalbot.config.FxProperties;
import io.github.signalbot.config.PaperWalletProperties;
import io.github.signalbot.domain.model.FeeBreakdown;
import io.github.signalbot.domain.model.Venue;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FeeModelTest {

    private final FxConverter fx = new FxConverter(new FxProperties(new BigDecimal("525"), new BigDecimal("565")));
    private final PaperWalletProperties props = new PaperWalletProperties(
            new BigDecimal("1000000"), new BigDecimal("0.001"),
            new BigDecimal("0.005"), new BigDecimal("1.00"), new BigDecimal("0.10"), 60);
    private final FeeModel feeModel = new FeeModel(fx, props);

    @Test
    void usVenueUsesPerSharePlusPerOrderConvertedToKzt() {
        // 0.005 * 10 * 525 = 26.25 ; 1.00 * 525 = 525 ; total = 551.25
        FeeBreakdown fee = feeModel.compute(Venue.US, new BigDecimal("10"), new BigDecimal("999999"));
        assertEquals(0, fee.perShareKzt().compareTo(new BigDecimal("26.25")));
        assertEquals(0, fee.perOrderKzt().compareTo(new BigDecimal("525")));
        assertEquals(0, fee.percentageKzt().compareTo(BigDecimal.ZERO));
        assertEquals(0, fee.totalKzt().compareTo(new BigDecimal("551.25")));
        assertEquals("us-per-share", fee.model());
    }

    @Test
    void nonUsVenueFallsBackToPercentOfNotional() {
        FeeBreakdown fee = feeModel.compute(Venue.KASE, new BigDecimal("10"), new BigDecimal("100000"));
        assertEquals(0, fee.percentageKzt().compareTo(new BigDecimal("100")));
        assertEquals(0, fee.perShareKzt().compareTo(BigDecimal.ZERO));
        assertEquals(0, fee.totalKzt().compareTo(new BigDecimal("100")));
        assertEquals("percentage", fee.model());
    }
}
