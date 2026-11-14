-- Increment 2 seed: paper wallet starts with 300,000 KZT and nothing else.
INSERT INTO cash_balance (currency, balance, reserved_by_open_buy_orders)
VALUES ('KZT', 300000, 0)
ON CONFLICT (currency) DO NOTHING;
