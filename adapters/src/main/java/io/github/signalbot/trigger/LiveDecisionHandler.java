package io.github.signalbot.trigger;

import io.github.signalbot.application.risk.RiskContext;
import io.github.signalbot.application.risk.RiskDecision;
import io.github.signalbot.application.risk.RiskLimitEvaluator;
import io.github.signalbot.application.trigger.TriggerSignal;
import io.github.signalbot.config.FxConverter;
import io.github.signalbot.domain.model.Action;
import io.github.signalbot.domain.model.Ccy;
import io.github.signalbot.domain.model.DecisionContext;
import io.github.signalbot.domain.model.Portfolio;
import io.github.signalbot.domain.model.Position;
import io.github.signalbot.domain.model.Verdict;
import io.github.signalbot.domain.port.DecisionEngine;
import io.github.signalbot.domain.port.Notifier;
import io.github.signalbot.execution.ExecutionService;
import io.github.signalbot.execution.ExecutionSource;
import io.github.signalbot.persistence.DailyPnlStore;
import io.github.signalbot.persistence.TradeQueryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** The live path: decide -> enforce risk limits -> submit a confirmation-gated execution. */
@Component
public class LiveDecisionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(LiveDecisionHandler.class);
    private static final ZoneId ALMATY = ZoneId.of("Asia/Almaty");

    private final ObjectProvider<DecisionEngine> decisionEngine;
    private final ContextAssembler assembler;
    private final RiskLimitEvaluator riskEvaluator;
    private final ObjectProvider<Notifier> notifier;
    private final TradeQueryStore tradeQuery;
    private final DailyPnlStore dailyPnl;
    private final FxConverter fx;
    private final ExecutionService executionService;
    private final Map<String, Verdict> lastVerdict = new ConcurrentHashMap<>();

    public LiveDecisionHandler(ObjectProvider<DecisionEngine> decisionEngine, ContextAssembler assembler,
                               RiskLimitEvaluator riskEvaluator, ObjectProvider<Notifier> notifier,
                               TradeQueryStore tradeQuery, DailyPnlStore dailyPnl, FxConverter fx,
                               ExecutionService executionService) {
        this.decisionEngine = decisionEngine;
        this.assembler = assembler;
        this.riskEvaluator = riskEvaluator;
        this.notifier = notifier;
        this.tradeQuery = tradeQuery;
        this.dailyPnl = dailyPnl;
        this.fx = fx;
        this.executionService = executionService;
    }

    public void handle(TriggerSignal signal) {
        DecisionEngine engine = decisionEngine.getIfAvailable();
        if (engine == null) {
            LOG.info("Decision engine disabled; cannot run live dispatch for {}", signal.symbol());
            return;
        }
        String symbol = signal.symbol();
        DecisionContext ctx = assembler.assemble(symbol, signal.reason(), lastVerdict.get(symbol));
        Verdict verdict = engine.decide(ctx);
        lastVerdict.put(symbol, verdict);
        Instant now = Instant.now();

        if (!verdict.isTrade()) {
            notify(verdict, "HOLD");
            return;
        }
        RiskDecision risk = riskEvaluator.evaluate(verdict, riskContext(symbol, verdict, ctx, now));
        if (!risk.allowed()) {
            LOG.info("REJECTED {} {}: {}", verdict.action(), symbol, risk.message());
            notify(verdict, "REJECTED: " + risk.message());
            return;
        }
        executionService.submit(verdict, ExecutionSource.VERDICT, refPrice(verdict, ctx));
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

    private void notify(Verdict verdict, String summary) {
        Notifier n = notifier.getIfAvailable();
        if (n != null) {
            n.send(verdict, summary);
        } else {
            LOG.info("Verdict {} {} - {}", verdict.action(), verdict.symbol(), summary);
        }
    }
}
