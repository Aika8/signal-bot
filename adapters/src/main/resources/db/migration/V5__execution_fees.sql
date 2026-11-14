-- Increment 2 follow-up: store the fee breakdown on each execution.
ALTER TABLE executions
    ADD COLUMN fee_per_share  NUMERIC(20, 4),
    ADD COLUMN fee_per_order  NUMERIC(20, 4),
    ADD COLUMN fee_percentage NUMERIC(20, 4),
    ADD COLUMN fee_total      NUMERIC(20, 4);
