package io.github.signalbot.trigger;

import io.github.signalbot.application.risk.RiskLimitEvaluator;
import io.github.signalbot.application.risk.RiskLimits;
import io.github.signalbot.application.trigger.TriggerSignal;
import io.github.signalbot.application.trigger.TriggerType;
import io.github.signalbot.config.FxConverter;
import io.github.signalbot.config.FxProperties;
import io.github.signalbot.config.PaperWalletProperties;
import io.github.signalbot.config.RiskProperties;
import io.github.signalbot.domain.model.Action;
import io.github.signalbot.domain.model.CashBalance;
import io.github.signalbot.domain.model.Ccy;
import io.github.signalbot.domain.model.DailyBar;
import io.github.signalbot.domain.model.DecisionContext;
import io.github.signalbot.domain.model.EarningsEvent;
import io.github.signalbot.domain.model.Portfolio;
import io.github.signalbot.domain.model.Position;
import io.github.signalbot.domain.model.PriceSnapshot;
import io.github.signalbot.domain.model.Venue;
import io.github.signalbot.domain.model.Verdict;
import io.github.signalbot.domain.port.DecisionEngine;
import io.github.signalbot.domain.port.EarningsCalendar;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
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
 * The earnings blackout on the live BUY path (D-006, SB-020), N = 2 trading days.
 *
 * <p>"Today" is Wednesday 2026-12-02 in New York (a fixed clock), so Thursday 12-03 is 1 trading day ahead and
 * Wednesday 12-09 is 5. Sizing numbers as in {@code LiveDecisionHandlerAtrTest}: USD/KZT 500, ATR 2, entry 100,
 * total 10 000 000 KZT → 49 shares.
 */
class LiveDecisionEarningsBlackoutTest {

    private static final String SYMBOL = "AAPL";
    private static final BigDecimal LAST = new BigDecimal("100");
    private static final BigDecimal TOTAL = new BigDecimal("10000000");
    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 12, 2);
    /** 15:00 UTC = 10:00 in New York, Wednesday. */
    private static final Clock WEDNESDAY_CLOCK = Clock.fixed(Instant.parse("2026-12-02T15:00:00Z"), ZoneOffset.UTC);

    private final DecisionEngine engine = mock(DecisionEngine.class);
    private final ContextAssembler assembler = mock(ContextAssembler.class);
    private final Notifier notifier = mock(Notifier.class);
    private final ExecutionService executions = mock(ExecutionService.class);
    private final PriceHistory history = mock(PriceHistory.class);
    private final EarningsCalendar calendar = mock(EarningsCalendar.class);
    private final TradeQueryStore tradeQuery = mock(TradeQueryStore.class);
    private final DailyPnlStore dailyPnl = mock(DailyPnlStore.class);
    private final FxConverter fx = new FxConverter(new FxProperties(new BigDecimal("500"), new BigDecimal("550")));
    private final FeeModel fees = new FeeModel(fx, new PaperWalletProperties(new BigDecimal("1000000"),
            new BigDecimal("0.001"), new BigDecimal("0.005"), new BigDecimal("1"), new BigDecimal("0.10"), 0));
    private final TriggerSignal signal = new TriggerSignal(SYMBOL, TriggerType.NEWS, "news", Instant.now());

    LiveDecisionEarningsBlackoutTest() {
        when(dailyPnl.realizedOn(any(LocalDate.class))).thenReturn(BigDecimal.ZERO);
        when(tradeQuery.countBetween(any(), any())).thenReturn(0);
        when(history.dailyBars(SYMBOL, LiveDecisionHandler.ATR_BARS)).thenReturn(flatBars(30));
        givenContext(List.of());
    }

    @Test
    void buyWithEarningsInOneTradingDayIsRejectedWithTheReasonAndNeverSubmitted() {
        Verdict claude = buy();
        when(calendar.upcoming(SYMBOL, WEDNESDAY, WEDNESDAY.plusDays(14)))
                .thenReturn(List.of(new EarningsEvent(SYMBOL, LocalDate.of(2026, 12, 3))));

        handler(2, true, WEDNESDAY_CLOCK).handle(signal);

        verify(notifier).send(same(claude), contains("REJECTED: earnings on 2026-12-03, within 2 trading days"));
        verifyNoSubmit();
    }

    @Test
    void buyWithEarningsTodayIsRejected() {
        Verdict claude = buy();
        when(calendar.upcoming(eq(SYMBOL), any(), any()))
                .thenReturn(List.of(new EarningsEvent(SYMBOL, WEDNESDAY)));

        handler(2, true, WEDNESDAY_CLOCK).handle(signal);

        verify(notifier).send(same(claude), contains("REJECTED: earnings on 2026-12-02"));
        verifyNoSubmit();
    }

    @Test
    void buyWithEarningsInFiveTradingDaysIsSubmittedSized() {
        buy();
        when(calendar.upcoming(SYMBOL, WEDNESDAY, WEDNESDAY.plusDays(14)))
                .thenReturn(List.of(new EarningsEvent(SYMBOL, LocalDate.of(2026, 12, 9))));

        handler(2, true, WEDNESDAY_CLOCK).handle(signal);

        assertSubmittedWith49Shares();
        verify(notifier, never()).send(any(), any());
    }

    @Test
    void todayIsTheNewYorkDateNotTheUtcDate() {
        // 03:00 UTC Thursday is still Wednesday 22:00 in New York.
        buy();
        Clock lateWednesdayInNewYork = Clock.fixed(Instant.parse("2026-12-03T03:00:00Z"), ZoneOffset.UTC);
        when(calendar.upcoming(eq(SYMBOL), any(), any())).thenReturn(List.of());

        handler(2, true, lateWednesdayInNewYork).handle(signal);

        verify(calendar).upcoming(SYMBOL, WEDNESDAY, WEDNESDAY.plusDays(14));
        assertSubmittedWith49Shares();
    }

    @Test
    void failingCalendarRejectsTheBuy() {
        Verdict claude = buy();
        when(calendar.upcoming(anyString(), any(), any()))
                .thenThrow(new SourceFailureException("finnhub/earnings", "HTTP 500"));

        handler(2, true, WEDNESDAY_CLOCK).handle(signal);

        verify(notifier).send(same(claude), contains("REJECTED: earnings calendar unavailable"));
        verifyNoSubmit();
    }

    @Test
    void noCalendarBeanSubmitsTheBuy() {
        buy();

        handler(2, false, WEDNESDAY_CLOCK).handle(signal);

        assertSubmittedWith49Shares();
    }

    @Test
    void blackoutOffNeverAsksTheCalendar() {
        buy();

        handler(0, true, WEDNESDAY_CLOCK).handle(signal);

        assertSubmittedWith49Shares();
        verifyNoInteractions(calendar);
    }

    @Test
    void elevenArgumentConstructorHasNoBlackout() {
        buy();
        @SuppressWarnings("unchecked")
        ObjectProvider<PriceHistory> histories = mock(ObjectProvider.class);
        when(histories.getIfAvailable()).thenReturn(history);

        new LiveDecisionHandler(engines(), assembler, new RiskLimitEvaluator(RiskLimits.defaults()), notifiers(),
                tradeQuery, dailyPnl, fx, executions, histories, RiskLimits.defaults(), fees).handle(signal);

        assertSubmittedWith49Shares();
    }

    @Test
    void sellIsNeverChecked() {
        Position held = new Position(SYMBOL, null, Venue.US, new BigDecimal("5"), new BigDecimal("5"),
                new BigDecimal("120"), LAST, Ccy.USD, new BigDecimal("250000"), BigDecimal.ZERO, BigDecimal.ZERO,
                false, false, Instant.now().minus(Duration.ofDays(5)));
        givenContext(List.of(held));
        Verdict claude = verdict(Action.SELL, "5");
        when(engine.decide(any())).thenReturn(claude);
        when(calendar.upcoming(anyString(), any(), any()))
                .thenReturn(List.of(new EarningsEvent(SYMBOL, LocalDate.of(2026, 12, 3))));

        handler(2, true, WEDNESDAY_CLOCK).handle(signal);

        verify(executions).submit(same(claude), eq(ExecutionSource.VERDICT), eq(LAST));
        verifyNoInteractions(calendar);
    }

    private Verdict buy() {
        Verdict claude = verdict(Action.BUY, "80");
        when(engine.decide(any())).thenReturn(claude);
        return claude;
    }

    private void assertSubmittedWith49Shares() {
        ArgumentCaptor<Verdict> submitted = ArgumentCaptor.forClass(Verdict.class);
        verify(executions).submit(submitted.capture(), eq(ExecutionSource.VERDICT), eq(LAST), any(SizingInfo.class));
        assertEquals(Action.BUY, submitted.getValue().action());
        assertEquals(0, new BigDecimal("49").compareTo(submitted.getValue().quantity()));
    }

    private void verifyNoSubmit() {
        verify(executions, never()).submit(any(), any(), any());
        verify(executions, never()).submit(any(), any(), any(), any());
    }

    private LiveDecisionHandler handler(int blackoutDays, boolean withCalendar, Clock clock) {
        @SuppressWarnings("unchecked")
        ObjectProvider<PriceHistory> histories = mock(ObjectProvider.class);
        when(histories.getIfAvailable()).thenReturn(history);
        @SuppressWarnings("unchecked")
        ObjectProvider<EarningsCalendar> calendars = mock(ObjectProvider.class);
        when(calendars.getIfAvailable()).thenReturn(withCalendar ? calendar : null);
        return new LiveDecisionHandler(engines(), assembler, new RiskLimitEvaluator(RiskLimits.defaults()),
                notifiers(), tradeQuery, dailyPnl, fx, executions, histories, RiskLimits.defaults(), fees,
                new RiskProperties(blackoutDays), calendars, clock);
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

    private void givenContext(List<Position> positions) {
        Portfolio portfolio = new Portfolio(positions, TOTAL, Map.of(Ccy.USD, new BigDecimal("500")), Instant.now());
        PriceSnapshot snapshot = new PriceSnapshot(SYMBOL, LAST, LAST, Instant.now());
        List<CashBalance> cash = List.of(new CashBalance(Ccy.KZT, new BigDecimal("5000000"),
                new BigDecimal("5000000"), BigDecimal.ZERO));
        when(assembler.assemble(any(), any(), any()))
                .thenReturn(new DecisionContext(SYMBOL, "news", portfolio, cash, List.of(), snapshot, null, true));
    }

    private static Verdict verdict(Action action, String quantity) {
        return new Verdict(SYMBOL, action, new BigDecimal(quantity), new BigDecimal("101"), null, null, 0.7, "test",
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
