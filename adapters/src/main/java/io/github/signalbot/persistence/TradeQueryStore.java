package io.github.signalbot.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Read-side queries over the paper trade log. */
@Repository
public class TradeQueryStore {

    /**
     * A BUY trade of the position that is open now, with its execution's 1R and total fee (null when the trade
     * was booked outside the execution flow, or the execution has no such value).
     */
    public record OpeningBuy(BigDecimal quantity, BigDecimal riskKzt, BigDecimal feeTotalKzt) {
    }

    private final JdbcClient db;

    public TradeQueryStore(JdbcClient db) {
        this.db = db;
    }

    public long totalCount() {
        Long count = db.sql("SELECT COUNT(*) FROM paper_trade").query(Long.class).single();
        return count == null ? 0L : count;
    }

    public int countBetween(Instant from, Instant to) {
        Integer count = db.sql("SELECT COUNT(*) FROM paper_trade WHERE filled_at >= :from AND filled_at < :to")
                .param("from", java.sql.Timestamp.from(from))
                .param("to", java.sql.Timestamp.from(to))
                .query(Integer.class)
                .single();
        return count == null ? 0 : count;
    }

    public List<String> summariesBetween(Instant from, Instant to) {
        return db.sql("""
                        SELECT sec_code, action, quantity, fill_price, realized_gain
                        FROM paper_trade WHERE filled_at >= :from AND filled_at < :to
                        ORDER BY filled_at
                        """)
                .param("from", java.sql.Timestamp.from(from))
                .param("to", java.sql.Timestamp.from(to))
                .query((rs, i) -> String.format("%s %s x%s @ %s (realized %s KZT)",
                        rs.getString("action"), rs.getString("sec_code"),
                        rs.getBigDecimal("quantity").stripTrailingZeros().toPlainString(),
                        rs.getBigDecimal("fill_price").stripTrailingZeros().toPlainString(),
                        rs.getBigDecimal("realized_gain").stripTrailingZeros().toPlainString()))
                .list();
    }

    /**
     * The BUY trades of {@code symbol} filled at or after {@code openedAt} (the open position's opening time),
     * with {@code executions.risk_kzt} (SB-008) and {@code executions.fee_total} (V5) of their executions.
     */
    public List<OpeningBuy> openingBuys(String symbol, Instant openedAt) {
        return db.sql("""
                        SELECT t.quantity, e.risk_kzt, e.fee_total
                        FROM paper_trade t LEFT JOIN executions e ON e.id = t.execution_id
                        WHERE t.sec_code = :symbol AND t.action = 'BUY' AND t.filled_at >= :openedAt
                        ORDER BY t.filled_at
                        """)
                .param("symbol", symbol)
                .param("openedAt", java.sql.Timestamp.from(openedAt))
                .query((rs, i) -> new OpeningBuy(rs.getBigDecimal("quantity"), rs.getBigDecimal("risk_kzt"),
                        rs.getBigDecimal("fee_total")))
                .list();
    }

    /** Sets {@code paper_trade.result_r} on the trade of {@code executionId}; returns the number of rows updated. */
    public int setResultR(long executionId, BigDecimal resultR) {
        return db.sql("UPDATE paper_trade SET result_r = :r WHERE execution_id = :executionId")
                .param("r", resultR).param("executionId", executionId)
                .update();
    }

    /**
     * Results in R ({@code paper_trade.result_r}, SB-015) of every closed trade so far — every SELL with a result in
     * R, oldest first. Input for the nightly review's expectancy (SB-016).
     */
    public List<BigDecimal> closedResultsR() {
        return db.sql("""
                        SELECT result_r FROM paper_trade
                        WHERE action = 'SELL' AND result_r IS NOT NULL
                        ORDER BY filled_at, id
                        """)
                .query(BigDecimal.class)
                .list();
    }

    /** Number of SELL trades so far without a result in R (no usable 1R), so left out of the expectancy. */
    public long closedWithoutRCount() {
        Long count = db.sql("SELECT COUNT(*) FROM paper_trade WHERE action = 'SELL' AND result_r IS NULL")
                .query(Long.class).single();
        return count == null ? 0L : count;
    }
}
