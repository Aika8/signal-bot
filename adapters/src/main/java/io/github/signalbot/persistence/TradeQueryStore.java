package io.github.signalbot.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/** Read-side queries over the paper trade log. */
@Repository
public class TradeQueryStore {

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
}
