package io.github.signalbot.trigger;

import io.github.signalbot.application.risk.RiskLimitEvaluator;
import io.github.signalbot.application.risk.RiskLimits;
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
import io.github.signalbot.domain.model.PriceSnapshot;
import io.github.signalbot.domain.model.Verdict;
import io.github.signalbot.domain.port.DecisionEngine;
import io.github.signalbot.domain.port.Notifier;
import io.github.signalbot.domain.port.PriceHistory;
import io.github.signalbot.execution.ExecutionService;
import io.github.signalbot.persistence.DailyPnlStore;
import io.github.signalbot.persistence.FeeModel;
import io.github.signalbot.persistence.TradeQueryStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D-007 (SB-031) in the live path: a BUY whose stop would be at or below zero is REJECTED "no usable stop" and never
 * submitted. Bars of close 3, high 4, low 2: every true range is 2, so ATR(14) = 2 and the ATR stop is 3 − 4 = −1.
 */
class LiveDecisionNonPositiveStopTest {

    private static final String SYMBOL = "PENNY";
    private static final BigDecimal LAST = new BigDecimal("3");
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

    LiveDecisionNonPositiveStopTest() {
        when(dailyPnl.realizedOn(any(LocalDate.class))).thenReturn(BigDecimal.ZERO);
        when(tradeQuery.countBetween(any(), any())).thenReturn(0);
        givenContext();
        when(history.dailyBars(SYMBOL, LiveDecisionHandler.ATR_BARS)).thenReturn(bars(30));
    }

    @Test
    void buyWithoutClaudeStopAndAtrStopBelowZeroIsRejectedNoUsableStop() {
        Verdict claude = verdict(null);
        when(engine.decide(any())).thenReturn(claude);

        handler().handle(signal);

        verify(notifier).send(same(claude), contains("REJECTED: no usable stop: the stop would be at or below zero"));
        verify(notifier, never()).send(any(), contains("no ATR"));
        verifyNoSubmit();
    }

    @Test
    void buyWithClaudeStopOfZeroAndAtrStopBelowZeroIsRejectedNoUsableStop() {
        Verdict claude = verdict("0");
        when(engine.decide(any())).thenReturn(claude);

        handler().handle(signal);

        verify(notifier).send(same(claude), contains("REJECTED: no usable stop"));
        verifyNoSubmit();
    }

    @Test
    void buyWithTooFewBarsIsStillRejectedNoAtr() {
        when(history.dailyBars(SYMBOL, LiveDecisionHandler.ATR_BARS)).thenReturn(bars(3));
        Verdict claude = verdict(null);
        when(engine.decide(any())).thenReturn(claude);

        handler().handle(signal);

        verify(notifier).send(same(claude), contains("REJECTED: no ATR"));
        verifyNoSubmit();
    }

    private void verifyNoSubmit() {
        verify(executions, never()).submit(any(), any(), any());
        verify(executions, never()).submit(any(), any(), any(), any());
    }

    @SuppressWarnings("unchecked")
    private LiveDecisionHandler handler() {
        ObjectProvider<PriceHistory> histories = mock(ObjectProvider.class);
        when(histories.getIfAvailable()).thenReturn(history);
        ObjectProvider<DecisionEngine> engines = mock(ObjectProvider.class);
        when(engines.getIfAvailable()).thenReturn(engine);
        ObjectProvider<Notifier> notifiers = mock(ObjectProvider.class);
        when(notifiers.getIfAvailable()).thenReturn(notifier);
        return new LiveDecisionHandler(engines, assembler, new RiskLimitEvaluator(RiskLimits.defaults()),
                notifiers, tradeQuery, dailyPnl, fx, executions, histories, RiskLimits.defaults(), fees);
    }

    private void givenContext() {
        Portfolio portfolio = new Portfolio(List.of(), TOTAL, Map.of(Ccy.USD, new BigDecimal("500")), Instant.now());
        PriceSnapshot snapshot = new PriceSnapshot(SYMBOL, LAST, LAST, Instant.now());
        BigDecimal cash = new BigDecimal("5000000");
        when(assembler.assemble(any(), any(), any())).thenReturn(new DecisionContext(SYMBOL, "news", portfolio,
                List.of(new CashBalance(Ccy.KZT, cash, cash, BigDecimal.ZERO)), List.of(), snapshot, null, true));
    }

    private static Verdict verdict(String stop) {
        return new Verdict(SYMBOL, Action.BUY, new BigDecimal("100"), LAST, stop == null ? null : new BigDecimal(stop),
                null, 0.7, "test", Instant.now().plus(Duration.ofMinutes(15)));
    }

    private static List<DailyBar> bars(int count) {
        List<DailyBar> bars = new ArrayList<>();
        LocalDate day = LocalDate.of(2026, 10, 1);
        for (int i = 0; i < count; i++) {
            bars.add(new DailyBar(day.plusDays(i), LAST, new BigDecimal("4"), new BigDecimal("2"), LAST, 1000));
        }
        return bars;
    }
}
