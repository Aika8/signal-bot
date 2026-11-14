package io.github.signalbot.application.trigger;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TriggerEvaluatorTest {

    private static final Instant T0 = Instant.parse("2026-10-23T15:00:00Z");

    private TriggerEvaluator evaluator() {
        return new TriggerEvaluator(new TriggerSettings(new BigDecimal("0.03"), Duration.ofMinutes(15)));
    }

    @Test
    void firstTickOnlySetsReference() {
        assertTrue(evaluator().onPriceTick("AAPL", new BigDecimal("100"), T0).isEmpty());
    }

    @Test
    void threePercentMoveWithinWindowFires() {
        TriggerEvaluator e = evaluator();
        e.onPriceTick("AAPL", new BigDecimal("100"), T0);
        Optional<TriggerSignal> signal = e.onPriceTick("AAPL", new BigDecimal("103"), T0.plus(Duration.ofMinutes(5)));
        assertTrue(signal.isPresent());
        assertEquals(TriggerType.PRICE_MOVE, signal.get().type());
    }

    @Test
    void smallMoveDoesNotFire() {
        TriggerEvaluator e = evaluator();
        e.onPriceTick("AAPL", new BigDecimal("100"), T0);
        assertTrue(e.onPriceTick("AAPL", new BigDecimal("102"), T0.plus(Duration.ofMinutes(5))).isEmpty());
    }

    @Test
    void moveAfterWindowResetsReferenceInsteadOfFiring() {
        TriggerEvaluator e = evaluator();
        e.onPriceTick("AAPL", new BigDecimal("100"), T0);
        // 20 minutes later (> 15m window): treated as a new reference, no fire.
        assertTrue(e.onPriceTick("AAPL", new BigDecimal("120"), T0.plus(Duration.ofMinutes(20))).isEmpty());
        // A 3% move from the new reference within the window now fires.
        assertTrue(e.onPriceTick("AAPL", new BigDecimal("124"), T0.plus(Duration.ofMinutes(25))).isPresent());
    }

    @Test
    void setReferencePriceIsUsedAsBaseline() {
        TriggerEvaluator e = evaluator();
        e.setReferencePrice("AAPL", new BigDecimal("200"), T0);
        assertTrue(e.onPriceTick("AAPL", new BigDecimal("206"), T0.plus(Duration.ofMinutes(1))).isPresent());
    }

    @Test
    void newsAlwaysProducesSignal() {
        Optional<TriggerSignal> s = evaluator().onNews("AAPL", "Apple announces buyback", T0);
        assertTrue(s.isPresent());
        assertEquals(TriggerType.NEWS, s.get().type());
    }

    @Test
    void earningsFiresOncePerSymbolAndDate() {
        TriggerEvaluator e = evaluator();
        LocalDate date = LocalDate.of(2026, 10, 25);
        assertTrue(e.onEarnings("AAPL", date, T0).isPresent());
        assertTrue(e.onEarnings("AAPL", date, T0.plus(Duration.ofHours(1))).isEmpty());
        assertTrue(e.onEarnings("AAPL", date.plusDays(90), T0).isPresent());
    }
}
