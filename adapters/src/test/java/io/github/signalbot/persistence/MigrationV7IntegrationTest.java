package io.github.signalbot.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs in CI (needs Docker; skipped without it, and CI fails on a skip): V7 adds the ATR sizing, trailing and R columns. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@Import(PaperWalletIntegrationTest.FakeFeedConfig.class)
class MigrationV7IntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    JdbcClient db;

    record Expected(String table, String column, String dataType, Integer precision, Integer scale,
                    Integer length, boolean nullable) { }

    private static Expected numeric(String table, String column, int precision, int scale) {
        return new Expected(table, column, "numeric", precision, scale, null, true);
    }

    @Test
    void v7AddsEveryColumnWithTheRightTypeAndNullability() {
        List<Expected> expected = List.of(
                new Expected("executions", "stop_source", "character varying", null, null, 8, true),
                numeric("executions", "claude_stop", 20, 6),
                numeric("executions", "atr", 20, 6),
                numeric("executions", "risk_per_share", 20, 6),
                numeric("executions", "risk_kzt", 20, 2),
                numeric("executions", "claude_quantity", 20, 6),
                numeric("executions", "limit_price", 20, 6),
                numeric("position_guards", "tp_activation", 20, 6),
                numeric("position_guards", "trail_offset", 20, 6),
                numeric("position_guards", "trail_spread", 20, 6),
                numeric("position_guards", "best_price", 20, 6),
                new Expected("position_guards", "activated", "boolean", null, null, null, false),
                numeric("position_guards", "risk_kzt", 20, 2),
                numeric("paper_trade", "result_r", 12, 4));

        for (Expected e : expected) {
            Optional<Expected> actual = db.sql("""
                            SELECT table_name, column_name, data_type, numeric_precision, numeric_scale,
                                   character_maximum_length, is_nullable
                            FROM information_schema.columns
                            WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?
                            """)
                    .params(e.table(), e.column())
                    .query((rs, i) -> new Expected(
                            rs.getString("table_name"),
                            rs.getString("column_name"),
                            rs.getString("data_type"),
                            e.dataType().equals("numeric") ? rs.getInt("numeric_precision") : null,
                            e.dataType().equals("numeric") ? rs.getInt("numeric_scale") : null,
                            e.dataType().equals("character varying") ? rs.getInt("character_maximum_length") : null,
                            "YES".equals(rs.getString("is_nullable"))))
                    .optional();
            assertTrue(actual.isPresent(), "missing column " + e.table() + "." + e.column());
            assertEquals(e, actual.get());
        }
    }

    @Test
    void activatedDefaultsToFalseForANewGuard() {
        db.sql("INSERT INTO position_guards (sec_code) VALUES ('V7TEST')").update();
        Boolean activated = db.sql("SELECT activated FROM position_guards WHERE sec_code = 'V7TEST'")
                .query(Boolean.class).single();
        assertEquals(Boolean.FALSE, activated);
        db.sql("DELETE FROM position_guards WHERE sec_code = 'V7TEST'").update();
    }
}
