package io.github.signalbot.persistence;

import io.github.signalbot.application.trigger.TrailingLevels;
import io.github.signalbot.application.trigger.TrailingSnapshot;
import io.github.signalbot.application.trigger.TrailingState;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Types;
import java.util.List;

/** Persists the stop/take-profit levels so the exit guard survives restarts. */
@Repository
public class PositionGuardStore {

    public record GuardRow(String secCode, BigDecimal stop, BigDecimal takeProfit) {
    }

    /**
     * A persisted guard with its trailing take-profit (D-004, V7 columns). {@code trailing} is {@code null} when the
     * guard has no trailing armed (rows from before V7, or a BUY filled without an ATR); {@code best} is {@code null}
     * before activation.
     */
    public record StoredGuard(String secCode, BigDecimal stop, BigDecimal takeProfit, TrailingLevels trailing,
                              BigDecimal best, boolean activated, BigDecimal riskKzt) {
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

    /**
     * Stores the trailing take-profit armed at a BUY fill (D-004) on the guard row {@link #upsert} wrote, and resets
     * its state (not activated, no best price). {@code trailing == null} clears the trailing columns, so a guard
     * re-armed without an ATR does not keep the levels of an earlier position.
     */
    public void saveTrailing(String secCode, TrailingLevels trailing, BigDecimal riskKzt) {
        db.sql("""
                        UPDATE position_guards SET
                            tp_activation = :activation,
                            trail_offset = :offset,
                            trail_spread = :spread,
                            best_price = NULL,
                            activated = false,
                            risk_kzt = :riskKzt
                        WHERE sec_code = :sec
                        """)
                .param("activation", trailing == null ? null : trailing.activation(), Types.NUMERIC)
                .param("offset", trailing == null ? null : trailing.offset(), Types.NUMERIC)
                .param("spread", trailing == null ? null : trailing.spread(), Types.NUMERIC)
                .param("riskKzt", riskKzt, Types.NUMERIC)
                .param("sec", secCode)
                .update();
    }

    /**
     * Stores the trailing state (best price, activation) so a restart does not reset trailing (D-004).
     *
     * @deprecated writes onto whatever guard the row holds now, even one re-armed after the state was read (#30);
     *     use {@link #saveTrailingState(String, TrailingSnapshot)}.
     */
    @Deprecated
    public void saveTrailingState(String secCode, TrailingState state) {
        db.sql("UPDATE position_guards SET best_price = :best, activated = :activated WHERE sec_code = :sec")
                .param("best", state.best(), Types.NUMERIC)
                .param("activated", state.activated())
                .param("sec", secCode)
                .update();
    }

    /**
     * Stores the trailing state of a snapshot only on the guard row armed with the snapshot's trailing levels (#30):
     * a state read before a fill re-armed the row with new levels updates nothing. The levels are compared at the
     * columns' scale (V7: {@code NUMERIC(20, 6)}), so in-memory levels with more decimals still match their own row.
     *
     * @return whether a row was updated
     */
    public boolean saveTrailingState(String secCode, TrailingSnapshot snapshot) {
        int updated = db.sql("""
                        UPDATE position_guards SET best_price = :best, activated = :activated
                        WHERE sec_code = :sec
                          AND tp_activation = CAST(:activation AS NUMERIC(20, 6))
                          AND trail_offset = CAST(:offset AS NUMERIC(20, 6))
                        """)
                .param("best", snapshot.best(), Types.NUMERIC)
                .param("activated", snapshot.activated())
                .param("sec", secCode)
                .param("activation", snapshot.levels().activation(), Types.NUMERIC)
                .param("offset", snapshot.levels().offset(), Types.NUMERIC)
                .update();
        return updated > 0;
    }

    /** Every persisted guard with its trailing columns (V7). */
    public List<StoredGuard> findAllGuards() {
        return db.sql("""
                        SELECT sec_code, stop, take_profit, tp_activation, trail_offset, trail_spread,
                               best_price, activated, risk_kzt
                        FROM position_guards
                        """)
                .query((rs, i) -> {
                    BigDecimal activation = rs.getBigDecimal("tp_activation");
                    BigDecimal offset = rs.getBigDecimal("trail_offset");
                    BigDecimal spread = rs.getBigDecimal("trail_spread");
                    TrailingLevels trailing = activation == null || offset == null || spread == null
                            ? null : new TrailingLevels(activation, offset, spread);
                    return new StoredGuard(
                            rs.getString("sec_code"),
                            rs.getBigDecimal("stop"),
                            rs.getBigDecimal("take_profit"),
                            trailing,
                            rs.getBigDecimal("best_price"),
                            rs.getBoolean("activated"),
                            rs.getBigDecimal("risk_kzt"));
                })
                .list();
    }
}
