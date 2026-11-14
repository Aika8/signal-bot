package io.github.signalbot.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Appends every Claude request/response to the {@code decisions} table. */
@Repository
public class DecisionStore {

    private final JdbcClient db;

    public DecisionStore(JdbcClient db) {
        this.db = db;
    }

    public void record(String symbol, String triggerReason, String requestJson, String responseJson,
                       String action, Double confidence, String whatChanged) {
        db.sql("""
                        INSERT INTO decisions
                            (symbol, trigger_reason, request, response, action, confidence, what_changed)
                        VALUES (:symbol, :trigger, CAST(:request AS jsonb), CAST(:response AS jsonb),
                                :action, :confidence, :whatChanged)
                        """)
                .param("symbol", symbol)
                .param("trigger", triggerReason)
                .param("request", requestJson)
                .param("response", responseJson)
                .param("action", action)
                .param("confidence", confidence)
                .param("whatChanged", whatChanged)
                .update();
    }
}
