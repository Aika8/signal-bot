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
import io.github.signalbot.domain.model.PriceSnapshot;
import io.github.signalbot.domain.model.Verdict;
import io.github.signalbot.domain.port.DecisionEngine;
import io.github.signalbot.domain.port.Notifier;
import io.github.signalbot.domain.port.PriceHistory;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * SB-037 (#26): the BUY gate uses the ATR the context carried to Claude, and fetches daily bars only when the
 * context has none.
 *
 * <p>Numbers: USD/KZT 500, entry (last) 100 USD, total 10 000 000 KZT → 1R budget 100 000 KZT. Context ATR 4 → ATR
 * stop 100 − 2 × 4 = 92, risk 8 USD = 4 000 KZT a share → 25 shares before fees; fees 0.005 USD/share + 1 USD/order
 * = 1.125 USD = 562.5 KZT a leg, 1 125 KZT round trip → floor(98 875 / 4 000) = 24 shares. The bars (if they were
 * used) give ATR 2, stop 96, 49 shares — so the numbers tell which ATR was taken.
 */
class LiveDecisionContextAtrTest {

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

    LiveDecisionContextAtrTest() {
        when(dailyPnl.realizedOn(any(LocalDate.class))).thenReturn(BigDecimal.ZERO);
        when(tradeQuery.countBetween(any(), any())).thenReturn(0);
        when(history.dailyBars(SYMBOL, LiveDecisionHandler.ATR_BARS)).thenReturn(flatBars(30));
        when(engine.decide(any())).thenReturn(buy());
    }

    @Test
    void contextAtrSetsStopAndSizeWithoutFetchingBars() {
        givenContextAtr(new BigDecimal("4"));

        handler(true).handle(signal);

        ArgumentCaptor<Verdict> submitted = ArgumentCaptor.forClass(Verdict.class);
        ArgumentCaptor<SizingInfo> sizing = ArgumentCaptor.forClass(SizingInfo.class);
        verify(executions).submit(submitted.capture(), eq(ExecutionSource.VERDICT), eq(LAST), sizing.capture());
        assertEquals(0, new BigDecimal("92").compareTo(submitted.getValue().stop()));
        assertEquals(0, new BigDecimal("24").compareTo(submitted.getValue().quantity()));
        assertEquals(StopSource.ATR, sizing.getValue().stopSource());
        assertEquals(0, new BigDecimal("4").compareTo(sizing.getValue().atr()));
        assertEquals(0, new BigDecimal("8").compareTo(sizing.getValue().riskPerShare()));
        verify(history, never()).dailyBars(anyString(), anyInt());
    }

    @Test
    void contextAtrIsEnoughWithoutAPriceHistoryBean() {
        givenContextAtr(new BigDecimal("4"));

        handler(false).handle(signal);

        ArgumentCaptor<SizingInfo> sizing = ArgumentCaptor.forClass(SizingInfo.class);
        verify(executions).submit(any(), eq(ExecutionSource.VERDICT), eq(LAST), sizing.capture());
        assertEquals(0, new BigDecimal("4").compareTo(sizing.getValue().atr()));
        verifyNoInteractions(history);
    }

    @Test
    void contextWithoutAtrFetchesBarsAsBefore() {
        givenContextAtr(null);

        handler(true).handle(signal);

        ArgumentCaptor<Verdict> submitted = ArgumentCaptor.forClass(Verdict.class);
        ArgumentCaptor<SizingInfo> sizing = ArgumentCaptor.forClass(SizingInfo.class);
        verify(executions).submit(submitted.capture(), eq(ExecutionSource.VERDICT), eq(LAST), sizing.capture());
        assertEquals(0, new BigDecimal("96").compareTo(submitted.getValue().stop()));
        assertEquals(0, new BigDecimal("49").compareTo(submitted.getValue().quantity()));
        assertEquals(0, new BigDecimal("2").compareTo(sizing.getValue().atr()));
        verify(history).dailyBars(SYMBOL, LiveDecisionHandler.ATR_BARS);
    }

    @Test
    void nonPositiveContextAtrFallsBackToBars() {
        givenContextAtr(BigDecimal.ZERO);

        handler(true).handle(signal);

        ArgumentCaptor<SizingInfo> sizing = ArgumentCaptor.forClass(SizingInfo.class);
        verify(executions).submit(any(), eq(ExecutionSource.VERDICT), eq(LAST), sizing.capture());
        assertEquals(0, new BigDecimal("2").compareTo(sizing.getValue().atr()));
        verify(history).dailyBars(SYMBOL, LiveDecisionHandler.ATR_BARS);
    }

    private LiveDecisionHandler handler(boolean withHistory) {
        @SuppressWarnings("unchecked")
        ObjectProvider<PriceHistory> histories = mock(ObjectProvider.class);
        when(histories.getIfAvailable()).thenReturn(withHistory ? history : null);
        @SuppressWarnings("unchecked")
        ObjectProvider<DecisionEngine> engines = mock(ObjectProvider.class);
        when(engines.getIfAvailable()).thenReturn(engine);
        @SuppressWarnings("unchecked")
        ObjectProvider<Notifier> notifiers = mock(ObjectProvider.class);
        when(notifiers.getIfAvailable()).thenReturn(notifier);
        return new LiveDecisionHandler(engines, assembler, new RiskLimitEvaluator(RiskLimits.defaults()),
                notifiers, tradeQuery, dailyPnl, fx, executions, histories, RiskLimits.defaults(), fees);
    }

    private void givenContextAtr(BigDecimal atr) {
        Portfolio portfolio = new Portfolio(List.of(), TOTAL, Map.of(Ccy.USD, new BigDecimal("500")), Instant.now());
        PriceSnapshot snapshot = new PriceSnapshot(SYMBOL, LAST, LAST, Instant.now());
        List<CashBalance> cash = List.of(new CashBalance(Ccy.KZT, new BigDecimal("5000000"),
                new BigDecimal("5000000"), BigDecimal.ZERO));
        when(assembler.assemble(any(), any(), any())).thenReturn(
                new DecisionContext(SYMBOL, "news", portfolio, cash, List.of(), snapshot, null, true, atr));
    }

    private static Verdict buy() {
        return new Verdict(SYMBOL, Action.BUY, new BigDecimal("80"), new BigDecimal("101"), null, null, 0.7, "test",
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
