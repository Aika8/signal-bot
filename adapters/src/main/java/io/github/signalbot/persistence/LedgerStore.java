package io.github.signalbot.persistence;

import io.github.signalbot.domain.model.Action;
import io.github.signalbot.domain.model.Ccy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Appends to the trade log, tax ledger and daily P&amp;L. All KZT amounts. */
@Repository
public class LedgerStore {

    public record TradeRecord(
            String secCode, Action action, BigDecimal quantity, BigDecimal fillPrice,
            BigDecimal feeKzt, BigDecimal notionalKzt, BigDecimal realizedGainKzt,
            BigDecimal taxKzt, Ccy currency, String verdictSymbol, Instant filledAt, Long executionId) {
    }

    private final JdbcClient db;

    public LedgerStore(JdbcClient db) {
        this.db = db;
    }

    public long insertTrade(TradeRecord t) {
        return db.sql("""
                        INSERT INTO paper_trade
                            (sec_code, action, quantity, fill_price, fee, notional,
                             realized_gain, tax, currency, verdict_symbol, filled_at, execution_id)
                        VALUES (:sec, :action, :qty, :price, :fee, :notional,
                                :gain, :tax, :ccy, :verdict, :filledAt, :executionId)
                        RETURNING id
                        """)
                .param("sec", t.secCode())
                .param("action", t.action().name())
                .param("qty", t.quantity())
                .param("price", t.fillPrice())
                .param("fee", t.feeKzt())
                .param("notional", t.notionalKzt())
                .param("gain", t.realizedGainKzt())
                .param("tax", t.taxKzt())
                .param("ccy", t.currency().name())
                .param("verdict", t.verdictSymbol())
                .param("filledAt", java.sql.Timestamp.from(t.filledAt()))
                .param("executionId", t.executionId(), java.sql.Types.BIGINT)
                .query(Long.class)
                .single();
    }

    public void accrueTax(long tradeId, BigDecimal realizedGainKzt, BigDecimal taxKzt) {
        db.sql("""
                        INSERT INTO tax_ledger (trade_id, realized_gain, tax_amount)
                        VALUES (:tradeId, :gain, :tax)
                        """)
                .param("tradeId", tradeId)
                .param("gain", realizedGainKzt)
                .param("tax", taxKzt)
                .update();
    }

    public void addRealizedPnl(LocalDate date, BigDecimal realizedKzt) {
        db.sql("""
                        INSERT INTO daily_pnl (trade_date, realized_pnl)
                        VALUES (:date, :realized)
                        ON CONFLICT (trade_date) DO UPDATE SET
                            realized_pnl = daily_pnl.realized_pnl + EXCLUDED.realized_pnl
                        """)
                .param("date", java.sql.Date.valueOf(date))
                .param("realized", realizedKzt)
                .update();
    }
}
