-- Increment 2 step 2: manual confirmation flow.
-- Every BUY/SELL verdict and every protective exit becomes a PENDING execution,
-- confirmed (FILLED) or declined (SKIPPED) by the operator, or EXPIRED on timeout.
CREATE TABLE executions (
    id            BIGSERIAL      PRIMARY KEY,
    symbol        VARCHAR(32)    NOT NULL,
    action        VARCHAR(8)     NOT NULL,
    source        VARCHAR(16)    NOT NULL,   -- VERDICT | EXIT
    quantity      NUMERIC(20, 6) NOT NULL,
    stop          NUMERIC(20, 6),
    take_profit   NUMERIC(20, 6),
    ref_price     NUMERIC(20, 6),            -- last/tick price at push time (for slippage)
    state         VARCHAR(12)    NOT NULL,   -- PENDING | FILLED | SKIPPED | EXPIRED
    fill_price    NUMERIC(20, 6),
    fill_quantity NUMERIC(20, 6),
    pushed_at     TIMESTAMPTZ    NOT NULL,
    resolved_at   TIMESTAMPTZ,
    latency_ms    BIGINT,
    skip_reason   TEXT,
    created_at    TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX idx_executions_state ON executions (state);
CREATE INDEX idx_executions_pushed_at ON executions (pushed_at);
