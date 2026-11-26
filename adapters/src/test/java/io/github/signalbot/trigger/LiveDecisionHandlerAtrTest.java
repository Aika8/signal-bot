package io.github.signalbot.trigger;

import io.github.signalbot.application.risk.RiskLimitEvaluator;
import io.github.signalbot.application.risk.RiskLimits;
import io.github.signalbot.application.risk.StopSource;
import io.github.signalbot.application.trigger.TriggerSignal;
import io.github.signalbot.application.trigger.TriggerType;
import io.github.signalbot.config.FxConverter;
import io.github.signalbot.config.FxProperties;
import io.github.signalbot.config.PaperWalletProperties;
import io.github.signalbot.domain.model.Action;
import io.github.signalbot.domain.model.CashBalance;
import io.github.signalbot.domain.model.Ccy;
import io.github.signalbot.domain.model.DailyBar;
import io.github.signalbot.domain.model.DecisionContext;
import io.github.signalbot.domain.model.Portfolio;
import io.github.signalbot.domain.model.Position;
import io.github.signalbot.domain.model.PriceSnapshot;
import io.github.signalbot.domain.model.Venue;
import io.github.signalbot.domain.model.Verdict;
import io.github.signalbot.domain.port.DecisionEngine;
import io.github.signalbot.domain.port.DecisionFailedException;
import io.github.signalbot.domain.port.Notifier;
import io.github.signalbot.domain.port.PriceHistory;
import io.github.signalbot.domain.port.SourceFailureException;
import io.github.signalbot.execution.ExecutionService;
import io.github.signalbot.execution.ExecutionSource;
import io.github.signalbot.execution.SizingInfo;
import io.github.signalbot.persistence.DailyPnlStore;
import io.github.signalbot.persistence.FeeModel;
import io.github.signalbot.persistence.TradeQueryStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The live BUY path of SB-009 (D-002, D-003): ATR -> stop -> size -> limits -> submit with sizing facts.
 *
 * <p>Numbers: USD/KZT 500; 30 flat bars with high − low = 2, so ATR(14) = 2; entry (last) 100 USD, so the ATR stop
 * is 100 − 2 × 2 = 96 and the risk per share is 4 USD = 2 000 KZT. Total 10 000 000 KZT → 1R budget 100 000 KZT
 * → 50 shares before fees; US fees 0.005 USD/share + 1 USD/order → 625 KZT a leg for 50 shares, 1 250 KZT round
 * trip → floor(98 750 / 2 000) = 49 shares, 1R taken 98 000 KZT.
 */
class LiveDecisionHandlerAtrTest {

    private static final String SYMBOL = "AAPL";
    private static final BigDecimal LAST = new BigDecimal("100");
    private static final BigDecimal TOTAL = new BigDecimal("10000000");

    private final DecisionEngine engine = mock(DecisionEngine.class);
    private final ContextAssembler assembler = mock(ContextAssembler.class);
    private final Notifier notifier = mock(Notifier.class);
    private final ExecutionService executions = mock(ExecutionService.class);
    private final PriceHistory history = mock(PriceHistory.class);
    private final TradeQueryStore tradeQuery = mock(TradeQueryStore.class);
    private final DailyPnlStore dailyPnl = mock(DailyPnlStore.class);
    private final FxConverter fx = new FxConverter(new FxProperties(new BigDecimal("500"), new BigDecimal("550")));
    private final FeeModel fees = new FeeModel(fx, new PaperWalletProperties(new BigDecimal("1000000"),
            new BigDecimal("0.001"), new BigDecimal("0.005"), new BigDecimal("1"), new BigDecimal("0.10"), 0));
    private final TriggerSignal signal = new TriggerSignal(SYMBOL, TriggerType.NEWS, "news", Instant.now());

    LiveDecisionHandlerAtrTest() {
        when(dailyPnl.realizedOn(any(LocalDate.class))).thenReturn(BigDecimal.ZERO);
        when(tradeQuery.countBetween(any(), any())).thenReturn(0);
    }

    @Test
    void buyWithoutStopIsSubmittedWithAtrStopAndSizedQuantity() {
        givenContext(TOTAL, List.of(), cash("5000000"));
        Verdict claude = verdict(Action.BUY, "80", null);
        when(engine.decide(any())).thenReturn(claude);
        when(history.dailyBars(SYMBOL, LiveDecisionHandler.ATR_BARS)).thenReturn(flatBars(30));

        handler(true).handle(signal);

        ArgumentCaptor<Verdict> submitted = ArgumentCaptor.forClass(Verdict.class);
        ArgumentCaptor<SizingInfo> sizing = ArgumentCaptor.forClass(SizingInfo.class);
        verify(executions).submit(submitted.capture(), eq(ExecutionSource.VERDICT), eq(LAST), sizing.capture());
        assertEquals(0, new BigDecimal("49").compareTo(submitted.getValue().quantity()));
        assertEquals(0, new BigDecimal("96").compareTo(submitted.getValue().stop()));
        assertEquals(Action.BUY, submitted.getValue().action());
        SizingInfo facts = sizing.getValue();
        assertEquals(StopSource.ATR, facts.stopSource());
        assertNull(facts.claudeStop());
        assertEquals(0, new BigDecimal("2").compareTo(facts.atr()));
        assertEquals(0, new BigDecimal("4").compareTo(facts.riskPerShare()));
        assertEquals(0, new BigDecimal("98000").compareTo(facts.riskKzt()));
        assertSame(claude.quantity(), facts.claudeQuantity());
        verify(notifier, never()).send(any(), any());
    }

    @Test
    void claudeStopInsideTheAtrBandIsKept() {
        givenContext(TOTAL, List.of(), cash("5000000"));
        // 96.50 is 1.75 ATR below the entry: inside [1.5, 3] ATR.
        when(engine.decide(any())).thenReturn(verdict(Action.BUY, "10", "96.50"));
        when(history.dailyBars(SYMBOL, LiveDecisionHandler.ATR_BARS)).thenReturn(flatBars(30));

        handler(true).handle(signal);

        ArgumentCaptor<Verdict> submitted = ArgumentCaptor.forClass(Verdict.class);
        ArgumentCaptor<SizingInfo> sizing = ArgumentCaptor.forClass(SizingInfo.class);
        verify(executions).submit(submitted.capture(), eq(ExecutionSource.VERDICT), eq(LAST), sizing.capture());
        assertEquals(0, new BigDecimal("96.50").compareTo(submitted.getValue().stop()));
        assertEquals(StopSource.CLAUDE, sizing.getValue().stopSource());
        // Claude's 10 is below the risk size: Claude may only lower the size.
        assertEquals(0, BigDecimal.TEN.compareTo(submitted.getValue().quantity()));
    }

    @Test
    void concentrationCapsTheSizeInsteadOfRejecting() {
        // Held 2 600 000 KZT of AAPL; the 30 % cap leaves 400 000 KZT = 8 shares at 50 000 KZT.
        Position held = new Position(SYMBOL, null, Venue.US, new BigDecimal("52"), new BigDecimal("52"),
                new BigDecimal("90"), LAST, Ccy.USD, new BigDecimal("2600000"), BigDecimal.ZERO, BigDecimal.ZERO,
                false, false, Instant.now().minus(Duration.ofDays(5)));
        givenContext(TOTAL, List.of(held), cash("5000000"));
        when(engine.decide(any())).thenReturn(verdict(Action.BUY, "80", null));
        when(history.dailyBars(SYMBOL, LiveDecisionHandler.ATR_BARS)).thenReturn(flatBars(30));

        handler(true).handle(signal);

        ArgumentCaptor<Verdict> submitted = ArgumentCaptor.forClass(Verdict.class);
        verify(executions).submit(submitted.capture(), eq(ExecutionSource.VERDICT), eq(LAST), any(SizingInfo.class));
        assertEquals(0, new BigDecimal("8").compareTo(submitted.getValue().quantity()));
        verify(notifier, never()).send(any(), any());
    }

    @Test
    void buyWithNoBarsIsRejectedNoAtr() {
        givenContext(TOTAL, List.of(), cash("5000000"));
        Verdict claude = verdict(Action.BUY, "80", null);
        when(engine.decide(any())).thenReturn(claude);
        when(history.dailyBars(SYMBOL, LiveDecisionHandler.ATR_BARS)).thenReturn(List.of());

        handler(true).handle(signal);

        verify(notifier).send(same(claude), contains("REJECTED: no ATR"));
        verifyNoSubmit();
    }

    @Test
    void buyWhosePriceHistoryFailsIsRejectedNoAtr() {
        givenContext(TOTAL, List.of(), cash("5000000"));
        Verdict claude = verdict(Action.BUY, "80", null);
        when(engine.decide(any())).thenReturn(claude);
        when(history.dailyBars(anyString(), anyInt()))
                .thenThrow(new SourceFailureException("alpaca/bars", "HTTP 500"));

        handler(true).handle(signal);

        verify(notifier).send(same(claude), contains("REJECTED: no ATR"));
        verifyNoSubmit();
    }

    @Test
    void buyWithoutPriceHistoryBeanIsRejectedNoAtr() {
        givenContext(TOTAL, List.of(), cash("5000000"));
        Verdict claude = verdict(Action.BUY, "80", null);
        when(engine.decide(any())).thenReturn(claude);

        handler(false).handle(signal);

        verify(notifier).send(same(claude), contains("REJECTED: no ATR"));
        verifyNoSubmit();
    }

    @Test
    void delegatingConstructorRejectsBuyNoAtr() {
        givenContext(TOTAL, List.of(), cash("5000000"));
        Verdict claude = verdict(Action.BUY, "80", null);
        when(engine.decide(any())).thenReturn(claude);

        new LiveDecisionHandler(engines(), assembler, new RiskLimitEvaluator(RiskLimits.defaults()), notifiers(),
                tradeQuery, dailyPnl, fx, executions).handle(signal);

        verify(notifier).send(same(claude), contains("REJECTED: no ATR"));
        verifyNoSubmit();
    }

    @Test
    void buyWhoseSizeIsBelowOneShareIsRejectedWithTheReason() {
        // Total 100 000 KZT → 1R budget 1 000 KZT < 2 000 KZT risk per share.
        givenContext(new BigDecimal("100000"), List.of(), cash("100000"));
        Verdict claude = verdict(Action.BUY, "80", null);
        when(engine.decide(any())).thenReturn(claude);
        when(history.dailyBars(SYMBOL, LiveDecisionHandler.ATR_BARS)).thenReturn(flatBars(30));

        handler(true).handle(signal);

        verify(notifier).send(same(claude), contains("REJECTED: risk budget too small for this stop distance"));
        verifyNoSubmit();
    }

    @Test
    void buyWithoutEnoughCashIsRejectedWithTheReason() {
        givenContext(TOTAL, List.of(), cash("40000"));
        Verdict claude = verdict(Action.BUY, "80", null);
        when(engine.decide(any())).thenReturn(claude);
        when(history.dailyBars(SYMBOL, LiveDecisionHandler.ATR_BARS)).thenReturn(flatBars(30));

        handler(true).handle(signal);

        verify(notifier).send(same(claude), contains("REJECTED: available cash buys less than one share"));
        verifyNoSubmit();
    }

    @Test
    void sellIsUnchanged() {
        Position held = new Position(SYMBOL, null, Venue.US, new BigDecimal("5"), new BigDecimal("5"),
                new BigDecimal("120"), LAST, Ccy.USD, new BigDecimal("250000"), BigDecimal.ZERO, BigDecimal.ZERO,
                false, false, Instant.now().minus(Duration.ofDays(5)));
        givenContext(TOTAL, List.of(held), cash("5000000"));
        Verdict claude = verdict(Action.SELL, "7", null);
        when(engine.decide(any())).thenReturn(claude);

        handler(true).handle(signal);

        verify(executions).submit(same(claude), eq(ExecutionSource.VERDICT), eq(LAST));
        verify(executions, never()).submit(any(), any(), any(), any());
        verifyNoInteractions(history);
    }

    @Test
    void contextFailureNeverSubmitsWithSizing() {
        when(assembler.assemble(any(), any(), any()))
                .thenThrow(new SourceFailureException("finnhub/company-news", "expected a JSON array"));

        handler(true).handle(signal);

        verify(notifier).alert(eq(SYMBOL), contains("finnhub/company-news"));
        verifyNoSubmit();
        verifyNoInteractions(history);
    }

    @Test
    void decisionFailureNeverSubmitsWithSizing() {
        givenContext(TOTAL, List.of(), cash("5000000"));
        when(engine.decide(any())).thenThrow(new DecisionFailedException("no JSON object in response"));

        handler(true).handle(signal);

        verify(notifier).alert(eq(SYMBOL), contains("decision failed"));
        verifyNoSubmit();
        verifyNoInteractions(history);
    }

    private void verifyNoSubmit() {
        verify(executions, never()).submit(any(), any(), any());
        verify(executions, never()).submit(any(), any(), any(), any());
    }

    private LiveDecisionHandler handler(boolean withHistory) {
        @SuppressWarnings("unchecked")
        ObjectProvider<PriceHistory> histories = mock(ObjectProvider.class);
        when(histories.getIfAvailable()).thenReturn(withHistory ? history : null);
        return new LiveDecisionHandler(engines(), assembler, new RiskLimitEvaluator(RiskLimits.defaults()),
                notifiers(), tradeQuery, dailyPnl, fx, executions, histories, RiskLimits.defaults(), fees);
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<DecisionEngine> engines() {
        ObjectProvider<DecisionEngine> engines = mock(ObjectProvider.class);
        when(engines.getIfAvailable()).thenReturn(engine);
        return engines;
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<Notifier> notifiers() {
        ObjectProvider<Notifier> notifiers = mock(ObjectProvider.class);
        when(notifiers.getIfAvailable()).thenReturn(notifier);
        return notifiers;
    }

    private void givenContext(BigDecimal total, List<Position> positions, List<CashBalance> cash) {
        Portfolio portfolio = new Portfolio(positions, total, Map.of(Ccy.USD, new BigDecimal("500")), Instant.now());
        PriceSnapshot snapshot = new PriceSnapshot(SYMBOL, LAST, LAST, Instant.now());
        when(assembler.assemble(any(), any(), any()))
                .thenReturn(new DecisionContext(SYMBOL, "news", portfolio, cash, List.of(), snapshot, null, true));
    }

    private static List<CashBalance> cash(String kzt) {
        BigDecimal amount = new BigDecimal(kzt);
        return List.of(new CashBalance(Ccy.KZT, amount, amount, BigDecimal.ZERO));
    }

    private static Verdict verdict(Action action, String quantity, String stop) {
        return new Verdict(SYMBOL, action, new BigDecimal(quantity), new BigDecimal("101"),
                stop == null ? null : new BigDecimal(stop), null, 0.7, "test",
                Instant.now().plus(Duration.ofMinutes(15)));
    }

    /** Bars of close 100, high 101, low 99: every true range is 2, so ATR = 2. */
    private static List<DailyBar> flatBars(int count) {
        List<DailyBar> bars = new ArrayList<>();
        LocalDate day = LocalDate.of(2026, 10, 1);
        for (int i = 0; i < count; i++) {
            bars.add(new DailyBar(day.plusDays(i), LAST, new BigDecimal("101"), new BigDecimal("99"), LAST, 1000));
        }
        return bars;
    }
}
