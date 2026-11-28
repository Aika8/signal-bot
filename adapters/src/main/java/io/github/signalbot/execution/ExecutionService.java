package io.github.signalbot.execution;

import io.github.signalbot.application.trigger.ExitGuard;
import io.github.signalbot.application.trigger.TrailingLevels;
import io.github.signalbot.application.trigger.TrailingPolicy;
import io.github.signalbot.application.trigger.TriggerEvaluator;
import io.github.signalbot.config.ExecutionProperties;
import io.github.signalbot.domain.model.Action;
import io.github.signalbot.domain.model.PaperFill;
import io.github.signalbot.domain.model.PriceSnapshot;
import io.github.signalbot.domain.model.Verdict;
import io.github.signalbot.domain.port.PaperWallet;
import io.github.signalbot.execution.ExecutionResult.Outcome;
import io.github.signalbot.persistence.ExecutionStore;
import io.github.signalbot.persistence.ExecutionStore.ExecutionRow;
import io.github.signalbot.persistence.PositionGuardStore;
import io.github.signalbot.trigger.ContextAssembler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Confirmation-gated execution. A verdict/exit becomes a PENDING row and a
 * prompt; the paper wallet is applied only on FILL, at the operator's price.
 */
@Service
public class ExecutionService {

    private static final Logger LOG = LoggerFactory.getLogger(ExecutionService.class);

    private final ExecutionStore store;
    private final PaperWallet paperWallet;
    private final ObjectProvider<ExecutionGateway> gateway;
    private final ExecutionProperties props;
    private final ExitGuard exitGuard;
    private final PositionGuardStore guardStore;
    private final TriggerEvaluator triggerEvaluator;
    private final ContextAssembler assembler;
    private final TransactionOperations tx;

    public ExecutionService(ExecutionStore store, PaperWallet paperWallet, ObjectProvider<ExecutionGateway> gateway,
                            ExecutionProperties props, ExitGuard exitGuard, PositionGuardStore guardStore,
                            TriggerEvaluator triggerEvaluator, ContextAssembler assembler, TransactionOperations tx) {
        this.store = store;
        this.paperWallet = paperWallet;
        this.gateway = gateway;
        this.props = props;
        this.exitGuard = exitGuard;
        this.guardStore = guardStore;
        this.triggerEvaluator = triggerEvaluator;
        this.assembler = assembler;
        this.tx = tx;
    }

    /** Submit without sizing facts (exits, SELLs). */
    public long submit(Verdict verdict, ExecutionSource source, BigDecimal refPrice) {
        return submit(verdict, source, refPrice, null);
    }

    /** Submit a verdict; {@code sizing} is stored with the PENDING row, or {@code null} when there is none. */
    public long submit(Verdict verdict, ExecutionSource source, BigDecimal refPrice, SizingInfo sizing) {
        return submit(verdict, source, refPrice, sizing, null);
    }

    /**
     * Submit a verdict with a suggested limit price (a trailing exit, D-004: breakout − spread). The limit is
     * stored with the PENDING row before the prompt goes out, so the prompt can show it; {@code sizing} and
     * {@code limitPrice} may each be {@code null}.
     */
    public long submit(Verdict verdict, ExecutionSource source, BigDecimal refPrice, SizingInfo sizing,
                       BigDecimal limitPrice) {
        Instant now = Instant.now();
        long id;
        if (limitPrice != null) {
            id = store.insertPending(verdict.symbol(), verdict.action(), source, verdict.quantity(),
                    verdict.stop(), verdict.takeProfit(), refPrice, now, sizing, limitPrice);
        } else if (sizing == null) {
            id = store.insertPending(verdict.symbol(), verdict.action(), source, verdict.quantity(),
                    verdict.stop(), verdict.takeProfit(), refPrice, now);
        } else {
            id = store.insertPending(verdict.symbol(), verdict.action(), source, verdict.quantity(),
                    verdict.stop(), verdict.takeProfit(), refPrice, now, sizing);
        }
        ExecutionGateway gw = gateway.getIfAvailable();
        if (gw != null) {
            gw.promptExecution(id, verdict.symbol(), verdict.action(), verdict.quantity(), refPrice);
        } else {
            LOG.info("Execution {} PENDING ({} {} x{}); no confirmation channel", id,
                    verdict.action(), verdict.symbol(), verdict.quantity());
        }
        return id;
    }

    public Optional<ExecutionState> state(long id) {
        return store.find(id).map(ExecutionRow::state);
    }

    public ExecutionResult fill(long id, BigDecimal fillPrice) {
        Optional<ExecutionRow> row = store.find(id);
        if (row.isEmpty() || row.get().state() != ExecutionState.PENDING) {
            return unchanged(id, row);
        }
        return applyFill(row.get(), fillPrice);
    }

    /** Fill at the current last price (operator replied "ok", or AUTO mode). */
    public ExecutionResult fillAtLastPrice(long id) {
        Optional<ExecutionRow> row = store.find(id);
        if (row.isEmpty() || row.get().state() != ExecutionState.PENDING) {
            return unchanged(id, row);
        }
        PriceSnapshot snapshot = assembler.snapshot(row.get().symbol());
        if (snapshot == null || snapshot.last() == null) {
            LOG.warn("No last price for {}; cannot fill execution {}", row.get().symbol(), id);
            return new ExecutionResult(id, Outcome.NO_PRICE, ExecutionState.PENDING, null, null);
        }
        return applyFill(row.get(), snapshot.last());
    }

    public ExecutionResult skip(long id, String reason) {
        if (store.markSkipped(id, reason, Instant.now())) {
            return new ExecutionResult(id, Outcome.SKIPPED, ExecutionState.SKIPPED, null, reason);
        }
        return unchanged(id, store.find(id));
    }

    public void sweep() {
        Instant now = Instant.now();
        for (ExecutionRow row : store.findPending()) {
            Duration age = Duration.between(row.pushedAt(), now);
            if (props.mode() == ExecutionMode.AUTO && age.compareTo(props.autoFillDelay()) >= 0) {
                ExecutionResult result = fillAtLastPrice(row.id());
                if (result.outcome() != Outcome.FILLED) {
                    LOG.info("AUTO fill of execution {}: {}", row.id(), result.message());
                }
            } else if (age.compareTo(props.timeout()) >= 0 && store.markExpired(row.id(), now)) {
                LOG.info("Execution {} EXPIRED ({} {} x{})", row.id(), row.action(), row.symbol(), row.quantity());
            }
        }
    }

    /**
     * PENDING -> FILLED, the paper trade, the fee and the persisted guard commit
     * together or not at all. The conditional update decides the race between a
     * Telegram reply and the AUTO sweeper: the loser sees 0 rows and books nothing;
     * the unique execution_id on paper_trade backs this up.
     */
    private ExecutionResult applyFill(ExecutionRow row, BigDecimal fillPrice) {
        Instant now = Instant.now();
        long latencyMs = Duration.between(row.pushedAt(), now).toMillis();
        Verdict verdict = new Verdict(row.symbol(), row.action(), row.quantity(), fillPrice,
                row.stop(), row.takeProfit(), 1.0, "manual fill", now.plus(Duration.ofMinutes(15)));
        TrailingLevels trailing = row.action() == Action.BUY ? trailingFor(row, fillPrice) : null;
        PaperFill fill;
        try {
            fill = tx.execute(status -> {
                if (!store.markFilled(row.id(), fillPrice, row.quantity(), now, latencyMs)) {
                    return null;
                }
                PaperFill f = paperWallet.applyAt(verdict, fillPrice, row.id());
                store.recordFee(row.id(), f.feeBreakdown());
                if (row.action() == Action.BUY) {
                    guardStore.upsert(row.symbol(), row.stop(), row.takeProfit());
                    guardStore.saveTrailing(row.symbol(), trailing,
                            row.sizing() == null ? null : row.sizing().riskKzt());
                } else {
                    guardStore.delete(row.symbol());
                }
                return f;
            });
        } catch (RuntimeException e) {
            return failed(row, e);
        }
        if (fill == null) {
            return unchanged(row.id(), store.find(row.id()));
        }
        triggerEvaluator.setReferencePrice(row.symbol(), fillPrice, now);
        if (row.action() == Action.BUY && trailing != null) {
            exitGuard.armTrailing(row.symbol(), row.stop(), trailing);
        } else if (row.action() == Action.BUY) {
            // No fixed take-profit is armed any more (D-004); Claude's takeProfit stays on the execution for review.
            exitGuard.arm(row.symbol(), row.stop(), null);
        } else {
            exitGuard.disarm(row.symbol());
        }
        return new ExecutionResult(row.id(), Outcome.FILLED, ExecutionState.FILLED, fillPrice, null);
    }

    /**
     * The trailing take-profit of a BUY filled at {@code fillPrice}: the D-004 rule of {@link TrailingPolicy}, with
     * the ATR of the execution's sizing facts. {@code null} (stop only) when the policy gives none.
     */
    private static TrailingLevels trailingFor(ExecutionRow row, BigDecimal fillPrice) {
        BigDecimal atr = row.sizing() == null ? null : row.sizing().atr();
        Optional<TrailingLevels> levels = TrailingPolicy.forFill(fillPrice, row.stop(), atr);
        if (levels.isEmpty()) {
            LOG.info("BUY {} (execution {}) filled at {} with stop {} and ATR {}: no trailing take-profit "
                    + "(needs an ATR above zero and a fill above the stop); arming the stop only", row.symbol(),
                    row.id(), fillPrice, row.stop(), atr);
        }
        return levels.orElse(null);
    }

    private ExecutionResult failed(ExecutionRow row, RuntimeException e) {
        // The transaction rolled back. If someone else filled it meanwhile (unique
        // execution_id), report that; otherwise park it as FAILED so AUTO does not retry forever.
        Optional<ExecutionRow> current = store.find(row.id());
        if (current.isPresent() && current.get().state() != ExecutionState.PENDING) {
            return unchanged(row.id(), current);
        }
        String reason = e.getMessage();
        LOG.error("Paper apply failed for execution {} ({} {} x{}): {}", row.id(), row.action(), row.symbol(),
                row.quantity(), reason, e);
        if (!store.markFailed(row.id(), reason, Instant.now())) {
            return unchanged(row.id(), store.find(row.id()));
        }
        return new ExecutionResult(row.id(), Outcome.FAILED, ExecutionState.FAILED, null, reason);
    }

    private static ExecutionResult unchanged(long id, Optional<ExecutionRow> row) {
        return row.map(r -> new ExecutionResult(id, Outcome.NOT_PENDING, r.state(), null, null))
                .orElseGet(() -> new ExecutionResult(id, Outcome.NOT_FOUND, null, null, null));
    }
}
