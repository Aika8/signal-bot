package io.github.signalbot.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Appends every Claude request/response to the {@code decisions} table. */
@Repository
public class DecisionStore {

    /** Width of {@code decisions.trigger_reason} (V1: {@code VARCHAR(64)}, counted in characters = code points). */
    static final int TRIGGER_REASON_MAX = 64;
    private static final String ELLIPSIS = "\u2026";

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
                .param("trigger", fitTriggerReason(triggerReason))
                .param("request", requestJson)
                .param("response", responseJson)
                .param("action", action)
                .param("confidence", confidence)
                .param("whatChanged", whatChanged)
                .update();
    }

    /**
     * Cuts a reason longer than the column to {@value #TRIGGER_REASON_MAX} code points, the last one {@code …},
     * so a long news headline does not fail the INSERT. Cutting by code point never splits a surrogate pair.
     */
    static String fitTriggerReason(String reason) {
        if (reason == null || reason.codePointCount(0, reason.length()) <= TRIGGER_REASON_MAX) {
            return reason;
        }
        int end = reason.offsetByCodePoints(0, TRIGGER_REASON_MAX - 1);
        return reason.substring(0, end) + ELLIPSIS;
    }
}
