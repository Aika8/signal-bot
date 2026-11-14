package io.github.signalbot.persistence;

import io.github.signalbot.domain.model.Ccy;
import io.github.signalbot.domain.model.Venue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Reads and mutates the simulated positions (one row per symbol). */
@Repository
public class PositionStore {

    public record PaperPositionRow(
            String secCode, String isin, Venue venue, BigDecimal quantity,
            BigDecimal avgEntryPrice, Ccy priceCurrency, Instant openedAt) {
    }

    private final JdbcClient db;

    public PositionStore(JdbcClient db) {
        this.db = db;
    }

    public Optional<PaperPositionRow> find(String secCode) {
        return db.sql("SELECT * FROM paper_position WHERE sec_code = :s")
                .param("s", secCode)
                .query(this::map)
                .optional();
    }

    public List<PaperPositionRow> findAll() {
        return db.sql("SELECT * FROM paper_position ORDER BY sec_code")
                .query(this::map)
                .list();
    }

    public void upsert(PaperPositionRow row) {
        db.sql("""
                        INSERT INTO paper_position
                            (sec_code, isin, venue, quantity, avg_entry_price, price_currency, opened_at, updated_at)
                        VALUES (:sec, :isin, :venue, :qty, :avg, :ccy, :openedAt, now())
                        ON CONFLICT (sec_code) DO UPDATE SET
                            quantity = EXCLUDED.quantity,
                            avg_entry_price = EXCLUDED.avg_entry_price,
                            updated_at = now()
                        """)
                .param("sec", row.secCode())
                .param("isin", row.isin())
                .param("venue", row.venue().name())
                .param("qty", row.quantity())
                .param("avg", row.avgEntryPrice())
                .param("ccy", row.priceCurrency().name())
                .param("openedAt", java.sql.Timestamp.from(row.openedAt()))
                .update();
    }

    public void delete(String secCode) {
        db.sql("DELETE FROM paper_position WHERE sec_code = :s").param("s", secCode).update();
    }

    private PaperPositionRow map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new PaperPositionRow(
                rs.getString("sec_code"),
                rs.getString("isin"),
                Venue.valueOf(rs.getString("venue")),
                rs.getBigDecimal("quantity"),
                rs.getBigDecimal("avg_entry_price"),
                Ccy.valueOf(rs.getString("price_currency")),
                rs.getTimestamp("opened_at").toInstant());
    }
}
