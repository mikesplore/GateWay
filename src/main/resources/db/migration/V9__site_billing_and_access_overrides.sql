ALTER TABLE sites
    ADD COLUMN billing_amount NUMERIC(14, 2) NOT NULL DEFAULT 0 CHECK (billing_amount >= 0),
    ADD COLUMN billing_currency VARCHAR(3) NOT NULL DEFAULT 'KES',
    ADD COLUMN manual_block_reason VARCHAR(256);
