-- ATR plan step 5: columns for ATR sizing, trailing take-profit (D-004) and results in R.
-- Added before the code that writes them; all nullable (old rows stay valid) except
-- position_guards.activated, which defaults to false.

ALTER TABLE executions
    ADD COLUMN stop_source     VARCHAR(8),
    ADD COLUMN claude_stop     NUMERIC(20, 6),
    ADD COLUMN atr             NUMERIC(20, 6),
    ADD COLUMN risk_per_share  NUMERIC(20, 6),
    ADD COLUMN risk_kzt        NUMERIC(20, 2),
    ADD COLUMN claude_quantity NUMERIC(20, 6),
    ADD COLUMN limit_price     NUMERIC(20, 6);   -- suggested limit of a trailing exit (D-004)

ALTER TABLE position_guards
    ADD COLUMN tp_activation NUMERIC(20, 6),
    ADD COLUMN trail_offset  NUMERIC(20, 6),
    ADD COLUMN trail_spread  NUMERIC(20, 6),
    ADD COLUMN best_price    NUMERIC(20, 6),
    ADD COLUMN activated     BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN risk_kzt      NUMERIC(20, 2);

ALTER TABLE paper_trade
    ADD COLUMN result_r NUMERIC(12, 4);   -- realised result of a closing trade in R
