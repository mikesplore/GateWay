ALTER TABLE sites
    ADD COLUMN billing_amount NUMERIC(14, 2) NOT NULL DEFAULT 0 CHECK (billing_amount >= 0),
    ADD COLUMN manual_block_reason VARCHAR(256);
