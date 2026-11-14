-- signal-bot baseline schema (increment 1).
-- Paper wallet state, decision log, daily P&L and reviews.
-- Seeding (e.g. 300,000 KZT starting cash) is done in increment 2.

-- Free cash per currency (paper wallet).
CREATE TABLE cash_balance (
    currency                    VARCHAR(3)   PRIMARY KEY,
    balance                     NUMERIC(20, 4) NOT NULL DEFAULT 0,
    reserved_by_open_buy_orders NUMERIC(20, 4) NOT NULL DEFAULT 0,
    updated_at                  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Current simulated positions (one row per symbol).
CREATE TABLE paper_position (
    id              BIGSERIAL      PRIMARY KEY,
    sec_code        VARCHAR(32)    NOT NULL UNIQUE,
    isin            VARCHAR(12),
    venue           VARCHAR(16)    NOT NULL,
    quantity        NUMERIC(20, 6) NOT NULL,
    avg_entry_price NUMERIC(20, 6) NOT NULL,
    price_currency  VARCHAR(3)     NOT NULL,
    opened_at       TIMESTAMPTZ    NOT NULL,
    updated_at      TIMESTAMPTZ    NOT NULL DEFAULT now()
);

-- Every simulated fill.
CREATE TABLE paper_trade (
    id            BIGSERIAL      PRIMARY KEY,
    sec_code      VARCHAR(32)    NOT NULL,
    action        VARCHAR(8)     NOT NULL,
    quantity      NUMERIC(20, 6) NOT NULL,
    fill_price    NUMERIC(20, 6) NOT NULL,
    fee           NUMERIC(20, 4) NOT NULL DEFAULT 0,
    notional      NUMERIC(20, 4) NOT NULL,
    realized_gain NUMERIC(20, 4) NOT NULL DEFAULT 0,
    tax           NUMERIC(20, 4) NOT NULL DEFAULT 0,
    currency      VARCHAR(3)     NOT NULL,
    verdict_symbol VARCHAR(32),
    filled_at     TIMESTAMPTZ    NOT NULL,
    created_at    TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX idx_paper_trade_sec_code ON paper_trade (sec_code);
CREATE INDEX idx_paper_trade_filled_at ON paper_trade (filled_at);

-- Accrued tax on realized gains.
CREATE TABLE tax_ledger (
    id            BIGSERIAL      PRIMARY KEY,
    trade_id      BIGINT         NOT NULL REFERENCES paper_trade (id),
    realized_gain NUMERIC(20, 4) NOT NULL,
    tax_amount    NUMERIC(20, 4) NOT NULL,
    accrued_at    TIMESTAMPTZ    NOT NULL DEFAULT now()
);

-- One row per day: realized/unrealized P&L and portfolio value.
CREATE TABLE daily_pnl (
    trade_date     DATE           PRIMARY KEY,
    realized_pnl   NUMERIC(20, 4) NOT NULL DEFAULT 0,
    unrealized_pnl NUMERIC(20, 4) NOT NULL DEFAULT 0,
    total_value_kzt NUMERIC(20, 4) NOT NULL DEFAULT 0,
    pnl_pct        NUMERIC(10, 6) NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT now()
);

-- Every decision-engine request/response (the Claude call log).
CREATE TABLE decisions (
    id             BIGSERIAL    PRIMARY KEY,
    symbol         VARCHAR(32)  NOT NULL,
    trigger_reason VARCHAR(64)  NOT NULL,
    request        JSONB        NOT NULL,
    response       JSONB,
    action         VARCHAR(8),
    confidence     NUMERIC(5, 4),
    what_changed   TEXT,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_decisions_symbol ON decisions (symbol);
CREATE INDEX idx_decisions_created_at ON decisions (created_at);

-- Nightly written reviews.
CREATE TABLE daily_reviews (
    id          BIGSERIAL   PRIMARY KEY,
    review_date DATE        NOT NULL UNIQUE,
    summary     TEXT        NOT NULL,
    review      JSONB,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
