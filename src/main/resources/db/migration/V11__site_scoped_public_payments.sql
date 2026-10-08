ALTER TABLE payments
    ADD COLUMN site_id UUID REFERENCES sites(id) ON DELETE SET NULL;

CREATE INDEX payments_site_status_idx ON payments(site_id, status, created_at);

CREATE UNIQUE INDEX payments_one_open_site_payment_idx
    ON payments(site_id)
    WHERE site_id IS NOT NULL AND status IN ('initializing', 'pending');
