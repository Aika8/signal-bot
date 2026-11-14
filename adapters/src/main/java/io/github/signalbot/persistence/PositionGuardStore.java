package io.github.signalbot.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

/** Persists the stop/take-profit levels so the exit guard survives restarts. */
@Repository
public class PositionGuardStore {

    public record GuardRow(String secCode, BigDecimal stop, BigDecimal takeProfit) {
    }

    private final JdbcClient db;

    public PositionGuardStore(JdbcClient db) {
        this.db = db;
    }

    public void upsert(String secCode, BigDecimal stop, BigDecimal takeProfit) {
        db.sql("""
                        INSERT INTO position_guards (sec_code, stop, take_profit, armed_at)
                        VALUES (:sec, :stop, :tp, now())
                        ON CONFLICT (sec_code) DO UPDATE SET
                            stop = EXCLUDED.stop,
                            take_profit = EXCLUDED.take_profit,
                            armed_at = now()
                        """)
                .param("sec", secCode)
                .param("stop", stop)
                .param("tp", takeProfit)
                .update();
    }

    public void delete(String secCode) {
        db.sql("DELETE FROM position_guards WHERE sec_code = :sec").param("sec", secCode).update();
    }

    public List<GuardRow> findAll() {
        return db.sql("SELECT sec_code, stop, take_profit FROM position_guards")
                .query((rs, i) -> new GuardRow(
                        rs.getString("sec_code"),
                        rs.getBigDecimal("stop"),
                        rs.getBigDecimal("take_profit")))
                .list();
    }
}
