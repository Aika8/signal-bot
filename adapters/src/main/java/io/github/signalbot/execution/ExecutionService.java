package io.github.signalbot.execution;

import io.github.signalbot.application.trigger.ExitGuard;
import io.github.signalbot.application.trigger.TriggerEvaluator;
import io.github.signalbot.config.ExecutionProperties;
import io.github.signalbot.domain.model.Action;
import io.github.signalbot.domain.model.PaperFill;
import io.github.signalbot.domain.model.PriceSnapshot;
import io.github.signalbot.domain.model.Verdict;
import io.github.signalbot.domain.port.PaperWallet;
import io.github.signalbot.persistence.ExecutionStore;
import io.github.signalbot.persistence.ExecutionStore.ExecutionRow;
import io.github.signalbot.persistence.PositionGuardStore;
import io.github.signalbot.trigger.ContextAssembler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

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

    public ExecutionService(ExecutionStore store, PaperWallet paperWallet, ObjectProvider<ExecutionGateway> gateway,
                            ExecutionProperties props, ExitGuard exitGuard, PositionGuardStore guardStore,
                            TriggerEvaluator triggerEvaluator, ContextAssembler assembler) {
        this.store = store;
        this.paperWallet = paperWallet;
        this.gateway = gateway;
        this.props = props;
        this.exitGuard = exitGuard;
        this.guardStore = guardStore;
        this.triggerEvaluator = triggerEvaluator;
        this.assembler = assembler;
    }

    public long submit(Verdict verdict, ExecutionSource source, BigDecimal refPrice) {
        Instant now = Instant.now();
        long id = store.insertPending(verdict.symbol(), verdict.action(), source, verdict.quantity(),
                verdict.stop(), verdict.takeProfit(), refPrice, now);
        ExecutionGateway gw = gateway.getIfAvailable();
        if (gw != null) {
            gw.promptExecution(id, verdict.symbol(), verdict.action(), verdict.quantity(), refPrice);
        } else {
            LOG.info("Execution {} PENDING ({} {} x{}); no confirmation channel", id,
                    verdict.action(), verdict.symbol(), verdict.quantity());
        }
        return id;
    }

    public void fill(long id, BigDecimal fillPrice) {
        store.find(id).filter(r -> r.state() == ExecutionState.PENDING).ifPresentOrElse(
                row -> applyFill(row, fillPrice),
                () -> LOG.info("Execution {} not pending; fill ignored", id));
    }

    /** Fill at the current last price (operator replied "ok", or AUTO mode). */
    public void fillAtLastPrice(long id) {
        store.find(id).filter(r -> r.state() == ExecutionState.PENDING).ifPresent(row -> {
            PriceSnapshot snapshot = assembler.snapshot(row.symbol());
            if (snapshot == null || snapshot.last() == null) {
                LOG.warn("No last price for {}; cannot fill execution {}", row.symbol(), id);
                return;
            }
            applyFill(row, snapshot.last());
        });
    }

    public void skip(long id, String reason) {
        store.markSkipped(id, reason, Instant.now());
    }

    public void sweep() {
        Instant now = Instant.now();
        for (ExecutionRow row : store.findPending()) {
            Duration age = Duration.between(row.pushedAt(), now);
            if (props.mode() == ExecutionMode.AUTO && age.compareTo(props.autoFillDelay()) >= 0) {
                fillAtLastPrice(row.id());
            } else if (age.compareTo(props.timeout()) >= 0) {
                store.markExpired(row.id(), now);
                LOG.info("Execution {} EXPIRED ({} {} x{})", row.id(), row.action(), row.symbol(), row.quantity());
            }
        }
    }

    private void applyFill(ExecutionRow row, BigDecimal fillPrice) {
        Instant now = Instant.now();
        long latencyMs = Duration.between(row.pushedAt(), now).toMillis();
        store.markFilled(row.id(), fillPrice, row.quantity(), now, latencyMs);
        Verdict verdict = new Verdict(row.symbol(), row.action(), row.quantity(), fillPrice,
                row.stop(), row.takeProfit(), 1.0, "manual fill", now.plus(Duration.ofMinutes(15)));
        try {
            PaperFill fill = paperWallet.applyAt(verdict, fillPrice);
            store.recordFee(row.id(), fill.feeBreakdown());
        } catch (Exception e) {
            LOG.warn("Paper apply failed for execution {}: {}", row.id(), e.getMessage());
            return;
        }
        triggerEvaluator.setReferencePrice(row.symbol(), fillPrice, now);
        if (row.action() == Action.BUY) {
            exitGuard.arm(row.symbol(), row.stop(), row.takeProfit());
            guardStore.upsert(row.symbol(), row.stop(), row.takeProfit());
        } else {
            exitGuard.disarm(row.symbol());
            guardStore.delete(row.symbol());
        }
    }
}
