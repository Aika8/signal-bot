-- Idempotent fills: each confirmed execution produces at most one paper trade.
-- The unique index is the last line of defence behind the conditional
-- PENDING -> FILLED update (Telegram reply racing the AUTO sweeper).
-- Trades applied outside the execution flow keep execution_id NULL
-- (PostgreSQL allows many NULLs in a unique index).
ALTER TABLE paper_trade
    ADD COLUMN execution_id BIGINT REFERENCES executions (id);

CREATE UNIQUE INDEX uq_paper_trade_execution_id ON paper_trade (execution_id);
