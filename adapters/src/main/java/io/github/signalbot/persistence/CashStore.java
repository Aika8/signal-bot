package io.github.signalbot.persistence;

import io.github.signalbot.domain.model.Ccy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

/** Reads and mutates the paper cash balances. */
@Repository
public class CashStore {

    public record CashRow(Ccy currency, BigDecimal balance, BigDecimal reserved) {
    }

    private final JdbcClient db;

    public CashStore(JdbcClient db) {
        this.db = db;
    }

    public List<CashRow> findAll() {
        return db.sql("""
                        SELECT currency, balance, reserved_by_open_buy_orders
                        FROM cash_balance
                        ORDER BY currency
                        """)
                .query((rs, i) -> new CashRow(
                        Ccy.valueOf(rs.getString("currency")),
                        rs.getBigDecimal("balance"),
                        rs.getBigDecimal("reserved_by_open_buy_orders")))
                .list();
    }

    /** Sets the absolute balance of a currency, creating the row if absent. */
    public void setBalance(Ccy currency, BigDecimal amount) {
        int updated = db.sql("UPDATE cash_balance SET balance = :amount, updated_at = now() WHERE currency = :currency")
                .param("amount", amount).param("currency", currency.name()).update();
        if (updated == 0) {
            db.sql("INSERT INTO cash_balance (currency, balance, reserved_by_open_buy_orders) "
                            + "VALUES (:currency, :amount, 0)")
                    .param("currency", currency.name()).param("amount", amount).update();
        }
    }

    /** Adds {@code delta} (may be negative) to the balance of a currency, creating the row if absent. */
    public void adjust(Ccy currency, BigDecimal delta) {
        int updated = db.sql("""
                        UPDATE cash_balance SET balance = balance + :delta, updated_at = now()
                        WHERE currency = :currency
                        """)
                .param("delta", delta)
                .param("currency", currency.name())
                .update();
        if (updated == 0) {
            db.sql("""
                            INSERT INTO cash_balance (currency, balance, reserved_by_open_buy_orders)
                            VALUES (:currency, :delta, 0)
                            """)
                    .param("currency", currency.name())
                    .param("delta", delta)
                    .update();
        }
    }
}
