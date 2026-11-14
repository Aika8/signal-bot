package io.github.signalbot.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Reads the realized P&amp;L recorded for a given day. */
@Repository
public class DailyPnlStore {

    private final JdbcClient db;

    public DailyPnlStore(JdbcClient db) {
        this.db = db;
    }

    public BigDecimal realizedOn(LocalDate day) {
        return db.sql("""
                        SELECT COALESCE(
                            (SELECT realized_pnl FROM daily_pnl WHERE trade_date = :day), 0)
                        """)
                .param("day", java.sql.Date.valueOf(day))
                .query(BigDecimal.class)
                .single();
    }
}
