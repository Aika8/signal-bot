-- Increment 2: stop-loss / take-profit levels armed per held position.
-- Set when a BUY verdict is executed; cleared when the position is exited.
CREATE TABLE position_guards (
    sec_code    VARCHAR(32)    PRIMARY KEY,
    stop        NUMERIC(20, 6),
    take_profit NUMERIC(20, 6),
    armed_at    TIMESTAMPTZ    NOT NULL DEFAULT now()
);
