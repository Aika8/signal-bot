package io.github.signalbot.persistence;

import io.github.signalbot.domain.model.Action;
import io.github.signalbot.domain.model.FeeBreakdown;
import io.github.signalbot.execution.ExecutionSource;
import io.github.signalbot.execution.ExecutionState;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Persists confirmation-gated executions and their lifecycle. */
@Repository
public class ExecutionStore {

    public record ExecutionRow(
            long id, String symbol, Action action, ExecutionSource source, BigDecimal quantity,
            BigDecimal stop, BigDecimal takeProfit, BigDecimal refPrice, ExecutionState state, Instant pushedAt) {
    }

    public record SlippageRow(Action action, BigDecimal fillPrice, BigDecimal refPrice) {
    }

    public record FeeTotals(BigDecimal perShare, BigDecimal perOrder, BigDecimal percentage, BigDecimal total) {
    }

    private final JdbcClient db;

    public ExecutionStore(JdbcClient db) {
        this.db = db;
    }

    public long insertPending(String symbol, Action action, ExecutionSource source, BigDecimal quantity,
                              BigDecimal stop, BigDecimal takeProfit, BigDecimal refPrice, Instant pushedAt) {
        return db.sql("""
                        INSERT INTO executions (symbol, action, source, quantity, stop, take_profit,
                                                ref_price, state, pushed_at)
                        VALUES (:symbol, :action, :source, :qty, :stop, :tp, :ref, 'PENDING', :pushedAt)
                        RETURNING id
                        """)
                .param("symbol", symbol).param("action", action.name()).param("source", source.name())
                .param("qty", quantity).param("stop", stop).param("tp", takeProfit).param("ref", refPrice)
                .param("pushedAt", java.sql.Timestamp.from(pushedAt))
                .query(Long.class).single();
    }

    public Optional<ExecutionRow> find(long id) {
        return db.sql("SELECT * FROM executions WHERE id = :id").param("id", id).query(this::map).optional();
    }

    public List<ExecutionRow> findPending() {
        return db.sql("SELECT * FROM executions WHERE state = 'PENDING' ORDER BY pushed_at").query(this::map).list();
    }

    public void markFilled(long id, BigDecimal fillPrice, BigDecimal fillQuantity, Instant resolvedAt, long latencyMs) {
        db.sql("""
                        UPDATE executions SET state = 'FILLED', fill_price = :price, fill_quantity = :qty,
                            resolved_at = :resolved, latency_ms = :latency
                        WHERE id = :id AND state = 'PENDING'
                        """)
                .param("price", fillPrice).param("qty", fillQuantity)
                .param("resolved", java.sql.Timestamp.from(resolvedAt)).param("latency", latencyMs).param("id", id)
                .update();
    }

    public void markSkipped(long id, String reason, Instant resolvedAt) {
        db.sql("UPDATE executions SET state = 'SKIPPED', skip_reason = :reason, resolved_at = :resolved "
                        + "WHERE id = :id AND state = 'PENDING'")
                .param("reason", reason).param("resolved", java.sql.Timestamp.from(resolvedAt)).param("id", id)
                .update();
    }

    public void markExpired(long id, Instant resolvedAt) {
        db.sql("UPDATE executions SET state = 'EXPIRED', resolved_at = :resolved "
                        + "WHERE id = :id AND state = 'PENDING'")
                .param("resolved", java.sql.Timestamp.from(resolvedAt)).param("id", id)
                .update();
    }

    public List<Long> latenciesBetween(Instant from, Instant to) {
        return db.sql("SELECT latency_ms FROM executions WHERE state = 'FILLED' AND latency_ms IS NOT NULL "
                        + "AND resolved_at >= :from AND resolved_at < :to")
                .param("from", java.sql.Timestamp.from(from)).param("to", java.sql.Timestamp.from(to))
                .query(Long.class).list();
    }

    public int countInStateBetween(ExecutionState state, Instant from, Instant to) {
        Integer c = db.sql("SELECT COUNT(*) FROM executions WHERE state = :state "
                        + "AND resolved_at >= :from AND resolved_at < :to")
                .param("state", state.name()).param("from", java.sql.Timestamp.from(from))
                .param("to", java.sql.Timestamp.from(to)).query(Integer.class).single();
        return c == null ? 0 : c;
    }

    public void recordFee(long id, FeeBreakdown fee) {
        db.sql("""
                        UPDATE executions SET fee_per_share = :ps, fee_per_order = :po,
                            fee_percentage = :pc, fee_total = :t WHERE id = :id
                        """)
                .param("ps", fee.perShareKzt()).param("po", fee.perOrderKzt())
                .param("pc", fee.percentageKzt()).param("t", fee.totalKzt()).param("id", id)
                .update();
    }

    public FeeTotals feeTotalsBetween(Instant from, Instant to) {
        return db.sql("""
                        SELECT COALESCE(SUM(fee_per_share), 0) ps, COALESCE(SUM(fee_per_order), 0) po,
                               COALESCE(SUM(fee_percentage), 0) pc, COALESCE(SUM(fee_total), 0) t
                        FROM executions WHERE state = 'FILLED' AND resolved_at >= :from AND resolved_at < :to
                        """)
                .param("from", java.sql.Timestamp.from(from)).param("to", java.sql.Timestamp.from(to))
                .query((rs, i) -> new FeeTotals(rs.getBigDecimal("ps"), rs.getBigDecimal("po"),
                        rs.getBigDecimal("pc"), rs.getBigDecimal("t")))
                .single();
    }

    public List<SlippageRow> slippageBetween(Instant from, Instant to) {
        return db.sql("SELECT action, fill_price, ref_price FROM executions WHERE state = 'FILLED' "
                        + "AND fill_price IS NOT NULL AND ref_price IS NOT NULL "
                        + "AND resolved_at >= :from AND resolved_at < :to")
                .param("from", java.sql.Timestamp.from(from)).param("to", java.sql.Timestamp.from(to))
                .query((rs, i) -> new SlippageRow(Action.valueOf(rs.getString("action")),
                        rs.getBigDecimal("fill_price"), rs.getBigDecimal("ref_price")))
                .list();
    }

    private ExecutionRow map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new ExecutionRow(
                rs.getLong("id"), rs.getString("symbol"), Action.valueOf(rs.getString("action")),
                ExecutionSource.valueOf(rs.getString("source")), rs.getBigDecimal("quantity"),
                rs.getBigDecimal("stop"), rs.getBigDecimal("take_profit"), rs.getBigDecimal("ref_price"),
                ExecutionState.valueOf(rs.getString("state")), rs.getTimestamp("pushed_at").toInstant());
    }
}
