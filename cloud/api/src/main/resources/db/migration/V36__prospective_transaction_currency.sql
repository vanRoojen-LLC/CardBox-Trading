-- No default, backfill or rewriting immutable source events: old transactions remain unknown.
ALTER TABLE trades ADD COLUMN actual_payment_currency text;
ALTER TABLE trades ADD COLUMN actual_currency_confirmed_by uuid REFERENCES users(id);
ALTER TABLE trades ADD COLUMN actual_currency_confirmed_at timestamptz;
ALTER TABLE trades ADD CONSTRAINT trades_actual_currency_confirmation CHECK (
    (actual_payment_currency IS NULL AND actual_currency_confirmed_by IS NULL AND actual_currency_confirmed_at IS NULL)
    OR (actual_payment_currency IS NOT NULL AND actual_payment_currency ~ '^[A-Z]{3}$'
        AND actual_currency_confirmed_by IS NOT NULL AND actual_currency_confirmed_at IS NOT NULL)
);
