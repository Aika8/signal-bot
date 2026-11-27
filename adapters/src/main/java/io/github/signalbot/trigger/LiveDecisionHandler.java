package io.github.signalbot.trigger;

import io.github.signalbot.application.risk.Atr;
import io.github.signalbot.application.risk.EarningsBlackout;
import io.github.signalbot.application.risk.PositionSizer;
import io.github.signalbot.application.risk.RiskContext;
import io.github.signalbot.application.risk.RiskDecision;
import io.github.signalbot.application.risk.RiskLimitEvaluator;
import io.github.signalbot.application.risk.RiskLimits;
import io.github.signalbot.application.risk.SizeDecision;
import io.github.signalbot.application.risk.StopDecision;
import io.github.signalbot.application.risk.StopPolicy;
import io.github.signalbot.application.trigger.TriggerSignal;
import io.github.signalbot.config.FxConverter;
import io.github.signalbot.config.RiskProperties;
import io.github.signalbot.domain.model.Action;
import io.github.signalbot.domain.model.CashBalance;
import io.github.signalbot.domain.model.Ccy;
import io.github.signalbot.domain.model.DailyBar;
import io.github.signalbot.domain.model.DecisionContext;
import io.github.signalbot.domain.model.EarningsEvent;
import io.github.signalbot.domain.model.Portfolio;
import io.github.signalbot.domain.model.Position;
import io.github.signalbot.domain.model.Venue;
import io.github.signalbot.domain.model.Verdict;
import io.github.signalbot.domain.port.DecisionEngine;
import io.github.signalbot.domain.port.DecisionFailedException;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The live path: decide -> (BUY: earnings blackout, D-006 -> ATR -> stop -> size, D-002/D-003) -> enforce risk limits -> submit a
 * confirmation-gated execution. A BUY without an ATR or whose size is below one share is REJECTED and notified,
 * never submitted with a guessed stop or size. A SELL keeps Claude's quantity (the paper wallet caps it at the
 * held quantity).
 */
@Component
public class LiveDecisionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(LiveDecisionHandler.class);
    private static final ZoneId ALMATY = ZoneId.of("Asia/Almaty");
    /** Earnings dates are US exchange dates (D-006). */
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    /** Calendar days of earnings asked for; {@code TradingDays} never looks further ahead. */
    static final int EARNINGS_LOOKAHEAD_DAYS = 14;
    /** Daily bars asked for: ATR(14) needs 15, the rest lets Wilder's smoothing settle. */
    static final int ATR_BARS = 30;
    static final int ATR_PERIOD = 14;
    /** The live path trades US stocks only (Alpaca feeds, paper wallet default): USD prices, US fees. */
    private static final Ccy LIVE_CCY = Ccy.USD;
    private static final Venue LIVE_VENUE = Venue.US;

    private final ObjectProvider<DecisionEngine> decisionEngine;
    private final ContextAssembler assembler;
    private final RiskLimitEvaluator riskEvaluator;
    private final ObjectProvider<Notifier> notifier;
    private final TradeQueryStore tradeQuery;
    private final DailyPnlStore dailyPnl;
    private final FxConverter fx;
    private final ExecutionService executionService;
    private final ObjectProvider<PriceHistory> priceHistory;
    private final RiskLimits limits;
    private final PositionSizer sizer;
    private final FeeModel feeModel;
    private final int blackoutTradingDays;
    private final ObjectProvider<EarningsCalendar> earningsCalendar;
    private final Clock clock;
    private final Map<String, Verdict> lastVerdict = new ConcurrentHashMap<>();

    public LiveDecisionHandler(ObjectProvider<DecisionEngine> decisionEngine, ContextAssembler assembler,
                               RiskLimitEvaluator riskEvaluator, ObjectProvider<Notifier> notifier,
                               TradeQueryStore tradeQuery, DailyPnlStore dailyPnl, FxConverter fx,
                               ExecutionService executionService) {
        this(decisionEngine, assembler, riskEvaluator, notifier, tradeQuery, dailyPnl, fx, executionService,
                null, RiskLimits.defaults(), null);
    }

    /**
     * @param priceHistory daily bars for ATR; an absent bean rejects every BUY with "no ATR"
     * @param limits       the same limits the {@code riskEvaluator} enforces (risk per trade, concentration)
     * @param feeModel     estimated round-trip fees; not used when a BUY is rejected before sizing
     */
    public LiveDecisionHandler(ObjectProvider<DecisionEngine> decisionEngine, ContextAssembler assembler,
                               RiskLimitEvaluator riskEvaluator, ObjectProvider<Notifier> notifier,
                               TradeQueryStore tradeQuery, DailyPnlStore dailyPnl, FxConverter fx,
                               ExecutionService executionService, ObjectProvider<PriceHistory> priceHistory,
                               RiskLimits limits, FeeModel feeModel) {
        this(decisionEngine, assembler, riskEvaluator, notifier, tradeQuery, dailyPnl, fx, executionService,
                priceHistory, limits, feeModel, RiskProperties.blackoutOff(), null, Clock.systemUTC());
    }

    /**
     * @param risk             the earnings blackout in trading days (D-006); 0 = off
     * @param earningsCalendar the symbol's earnings; an absent bean means no blackout (logged), a failing one
     *                         rejects the BUY (D-001)
     * @param clock            "today" for the blackout, taken in {@code America/New_York}
     */
    @Autowired
    public LiveDecisionHandler(ObjectProvider<DecisionEngine> decisionEngine, ContextAssembler assembler,
                               RiskLimitEvaluator riskEvaluator, ObjectProvider<Notifier> notifier,
                               TradeQueryStore tradeQuery, DailyPnlStore dailyPnl, FxConverter fx,
                               ExecutionService executionService, ObjectProvider<PriceHistory> priceHistory,
                               RiskLimits limits, FeeModel feeModel, RiskProperties risk,
                               ObjectProvider<EarningsCalendar> earningsCalendar, Clock clock) {
        this.decisionEngine = decisionEngine;
        this.assembler = assembler;
        this.riskEvaluator = riskEvaluator;
        this.notifier = notifier;
        this.tradeQuery = tradeQuery;
        this.dailyPnl = dailyPnl;
        this.fx = fx;
        this.executionService = executionService;
        this.priceHistory = priceHistory;
        this.limits = limits;
        this.sizer = new PositionSizer(limits);
        this.feeModel = feeModel;
        this.blackoutTradingDays = risk == null ? 0 : risk.earningsBlackoutTradingDays();
        this.earningsCalendar = earningsCalendar;
        this.clock = clock;
    }

    public void handle(TriggerSignal signal) {
        DecisionEngine engine = decisionEngine.getIfAvailable();
        if (engine == null) {
            LOG.info("Decision engine disabled; cannot run live dispatch for {}", signal.symbol());
            return;
        }
        String symbol = signal.symbol();
        DecisionContext ctx;
        Verdict verdict;
        try {
            ctx = assembler.assemble(symbol, signal.reason(), lastVerdict.get(symbol));
        } catch (RuntimeException e) {
            fail(symbol, "context unavailable (" + e.getMessage() + ")", e);
            return;
        }
        try {
            verdict = engine.decide(ctx);
        } catch (DecisionFailedException e) {
            fail(symbol, "decision failed (" + e.getMessage() + ")", e);
            return;
        }
        lastVerdict.put(symbol, verdict);
        Instant now = Instant.now();

        if (!verdict.isTrade()) {
            notify(verdict, "HOLD");
            return;
        }
        if (verdict.action() == Action.BUY) {
            handleBuy(symbol, verdict, ctx, now);
            return;
        }
        if (!allowedByLimits(verdict, ctx, now)) {
            return;
        }
        executionService.submit(verdict, ExecutionSource.VERDICT, refPrice(verdict, ctx));
    }

    /** ATR -> stop -> size -> count limits -> submit with the sizing facts (D-002, D-003). */
    private void handleBuy(String symbol, Verdict verdict, DecisionContext ctx, Instant now) {
        BigDecimal entry = refPrice(verdict, ctx);
        if (entry.signum() <= 0) {
            reject(verdict, "no entry price");
            return;
        }
        Optional<String> blackout = earningsBlackout(symbol);
        if (blackout.isPresent()) {
            reject(verdict, blackout.get());
            return;
        }
        PriceHistory history = priceHistory == null ? null : priceHistory.getIfAvailable();
        if (history == null) {
            reject(verdict, "no ATR: no price history configured");
            return;
        }
        Optional<BigDecimal> atr;
        try {
            List<DailyBar> bars = history.dailyBars(symbol, ATR_BARS);
            atr = Atr.wilder(bars, ATR_PERIOD);
        } catch (SourceFailureException e) {
            LOG.warn("Price history failed for {}: {}", symbol, e.getMessage());
            reject(verdict, "no ATR: price history failed (" + e.getMessage() + ")");
            return;
        }
        Optional<StopDecision> stopDecision = StopPolicy.forBuy(entry, verdict.stop(), atr);
        if (stopDecision.isEmpty()) {
            reject(verdict, "no ATR: not enough daily bars for ATR(" + ATR_PERIOD + ")");
            return;
        }
        StopDecision stop = stopDecision.get();

        Portfolio portfolio = ctx.portfolio();
        BigDecimal total = portfolio == null ? null : portfolio.totalValueKzt();
        if (total == null || total.signum() <= 0) {
            reject(verdict, "no portfolio value to size from");
            return;
        }
        SizeDecision size = size(symbol, verdict, ctx, entry, stop.stop(), total);
        if (size.rejected()) {
            reject(verdict, size.rejection());
            return;
        }
        BigDecimal quantity = BigDecimal.valueOf(size.quantity());
        if (verdict.quantity() != null && verdict.quantity().compareTo(quantity) > 0) {
            LOG.info("BUY {}: Claude's quantity {} lowered to {} (limited by {})", symbol, verdict.quantity(),
                    quantity, size.limitedBy());
        }
        // entry = the price the size was computed at, so the evaluator's concentration check sees the same notional
        // the cap was sized for (the size never exceeds the cap, D-003).
        Verdict sized = new Verdict(verdict.symbol(), verdict.action(), quantity, entry, stop.stop(),
                verdict.takeProfit(), verdict.confidence(), verdict.rationale(), verdict.expiresAt());
        if (!allowedByLimits(sized, ctx, now)) {
            return;
        }
        SizingInfo sizing = new SizingInfo(stop.source(), stop.claudeStop(), stop.atr(), entry.subtract(stop.stop()),
                size.riskKzt(), verdict.quantity());
        executionService.submit(sized, ExecutionSource.VERDICT, entry, sizing);
    }

    /**
     * D-006: the rejection reason when the symbol reports earnings within the configured trading days, otherwise
     * empty. Off (0) or no calendar bean → no blackout; a failing calendar → rejected, never "no earnings" (D-001).
     */
    private Optional<String> earningsBlackout(String symbol) {
        if (blackoutTradingDays <= 0) {
            return Optional.empty();
        }
        EarningsCalendar calendar = earningsCalendar == null ? null : earningsCalendar.getIfAvailable();
        if (calendar == null) {
            LOG.info("No earnings calendar configured; earnings blackout skipped for BUY {}", symbol);
            return Optional.empty();
        }
        LocalDate today = LocalDate.now(clock.withZone(NEW_YORK));
        List<EarningsEvent> events;
        try {
            events = calendar.upcoming(symbol, today, today.plusDays(EARNINGS_LOOKAHEAD_DAYS));
        } catch (RuntimeException e) {
            LOG.warn("Earnings calendar failed for {}: {}", symbol, e.getMessage());
            return Optional.of("earnings calendar unavailable (" + e.getMessage() + ")");
        }
        return EarningsBlackout.blocks(today, events, blackoutTradingDays);
    }

    /**
     * D-003 through {@link PositionSizer}. Round-trip fees come from {@link FeeModel} for the size the other bounds
     * allow without fees (fees only lower the size, so this never under-counts them).
     */
    private SizeDecision size(String symbol, Verdict verdict, DecisionContext ctx, BigDecimal entry, BigDecimal stop,
                              BigDecimal total) {
        BigDecimal fxRate = fx.rateOf(LIVE_CCY);
        BigDecimal priceKzt = fx.toKzt(entry, LIVE_CCY);
        OptionalLong claudeQty = verdict.quantity() == null ? OptionalLong.empty()
                : OptionalLong.of(wholeShares(verdict.quantity().max(BigDecimal.ZERO)));
        Position held = findPosition(ctx.portfolio(), symbol);
        BigDecimal heldKzt = held == null || held.marketValueKzt() == null ? BigDecimal.ZERO : held.marketValueKzt();
        long concentrationQty = sharesFor(total.multiply(limits.maxSingleSymbolPct()).subtract(heldKzt), priceKzt);
        long cashQty = sharesFor(spendableKzt(ctx.cash()), priceKzt);

        SizeDecision beforeFees = sizer.size(total, entry, stop, fxRate, BigDecimal.ZERO, claudeQty,
                concentrationQty, cashQty);
        if (beforeFees.rejected()) {
            return beforeFees;
        }
        BigDecimal qty = BigDecimal.valueOf(beforeFees.quantity());
        BigDecimal notionalKzt = priceKzt.multiply(qty);
        BigDecimal oneLegKzt = feeModel.compute(LIVE_VENUE, qty, notionalKzt).totalKzt();
        BigDecimal roundTripKzt = oneLegKzt.multiply(BigDecimal.TWO);
        return sizer.size(total, entry, stop, fxRate, roundTripKzt, claudeQty, concentrationQty, cashQty);
    }

    /** Free cash in KZT across currencies, net of amounts reserved by open BUY orders. */
    private BigDecimal spendableKzt(List<CashBalance> cash) {
        BigDecimal sum = BigDecimal.ZERO;
        for (CashBalance c : cash) {
            BigDecimal balanceKzt = c.balanceKzt() != null ? c.balanceKzt() : fx.toKzt(c.balance(), c.currency());
            sum = sum.add(balanceKzt).subtract(fx.toKzt(c.reservedByOpenBuyOrders(), c.currency()));
        }
        return sum;
    }

    private static long sharesFor(BigDecimal amountKzt, BigDecimal priceKzt) {
        if (amountKzt.signum() <= 0 || priceKzt.signum() <= 0) {
            return 0;
        }
        return wholeShares(amountKzt.divide(priceKzt, 0, RoundingMode.FLOOR));
    }

    private static long wholeShares(BigDecimal value) {
        BigDecimal floor = value.setScale(0, RoundingMode.FLOOR);
        return floor.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0 ? Long.MAX_VALUE : floor.longValueExact();
    }

    private boolean allowedByLimits(Verdict verdict, DecisionContext ctx, Instant now) {
        RiskDecision risk = riskEvaluator.evaluate(verdict, riskContext(verdict.symbol(), verdict, ctx, now));
        if (!risk.allowed()) {
            reject(verdict, risk.message());
            return false;
        }
        return true;
    }

    private void reject(Verdict verdict, String reason) {
        LOG.info("REJECTED {} {}: {}", verdict.action(), verdict.symbol(), reason);
        notify(verdict, "REJECTED: " + reason);
    }

    private static BigDecimal refPrice(Verdict verdict, DecisionContext ctx) {
        if (ctx.snapshot() != null && ctx.snapshot().last() != null) {
            return ctx.snapshot().last();
        }
        return verdict.entry() != null ? verdict.entry() : BigDecimal.ZERO;
    }

    private RiskContext riskContext(String symbol, Verdict verdict, DecisionContext ctx, Instant now) {
        Portfolio portfolio = ctx.portfolio();
        BigDecimal total = portfolio == null ? BigDecimal.ZERO : portfolio.totalValueKzt();
        Position held = findPosition(portfolio, symbol);
        BigDecimal unitPrice = verdict.entry() != null ? verdict.entry()
                : (ctx.snapshot() != null ? ctx.snapshot().last() : BigDecimal.ZERO);
        BigDecimal orderNotionalKzt = fx.toKzt(unitPrice.multiply(verdict.quantity()), Ccy.USD);
        LocalDate today = LocalDate.now(ALMATY);
        Instant dayStart = today.atStartOfDay(ALMATY).toInstant();
        Instant dayEnd = today.plusDays(1).atStartOfDay(ALMATY).toInstant();
        BigDecimal dailyPnlPct = total.signum() > 0
                ? dailyPnl.realizedOn(today).divide(total, 6, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        boolean sellRealizesGain = verdict.action() == Action.SELL
                && held != null && unitPrice.compareTo(held.avgEntryPrice()) > 0;
        return new RiskContext(
                tradeQuery.countBetween(dayStart, dayEnd),
                portfolio == null ? 0 : portfolio.positions().size(),
                held != null,
                held != null ? held.openedAt() : null,
                total,
                held != null ? held.marketValueKzt() : BigDecimal.ZERO,
                orderNotionalKzt,
                dailyPnlPct,
                now,
                sellRealizesGain);
    }

    private static Position findPosition(Portfolio portfolio, String symbol) {
        if (portfolio == null) {
            return null;
        }
        return portfolio.positions().stream()
                .filter(p -> symbol.equals(p.secCode()))
                .findFirst()
                .orElse(null);
    }

    /** No verdict is built: log at ERROR and tell the operator, never fall back to HOLD. */
    private void fail(String symbol, String reason, Exception e) {
        LOG.error("NO VERDICT for {}: {}", symbol, reason, e);
        Notifier n = notifier.getIfAvailable();
        if (n != null) {
            n.alert(symbol, "NO VERDICT: " + reason);
        }
    }

    private void notify(Verdict verdict, String summary) {
        Notifier n = notifier.getIfAvailable();
        if (n != null) {
            n.send(verdict, summary);
        } else {
            LOG.info("Verdict {} {} - {}", verdict.action(), verdict.symbol(), summary);
        }
    }
}
