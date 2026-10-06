-- Correct uniqueness to match the owning account/provider scope.
ALTER TABLE payments DROP CONSTRAINT IF EXISTS payments_idempotency_key_key;
CREATE UNIQUE INDEX payments_account_idempotency_unique ON payments(account_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
ALTER TABLE payment_events DROP CONSTRAINT IF EXISTS payment_events_deduplication_key_key;
CREATE UNIQUE INDEX payment_events_provider_deduplication_unique ON payment_events(provider, deduplication_key);
ALTER TABLE payments DROP CONSTRAINT IF EXISTS payments_status_check;
ALTER TABLE payments ADD CONSTRAINT payments_status_check CHECK (status IN ('pending', 'initializing', 'succeeded', 'failed', 'reversed'));

CREATE TABLE sites (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    hostname VARCHAR(253) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT sites_hostname_nonempty CHECK (length(hostname) > 0),
    CONSTRAINT sites_account_hostname_unique UNIQUE(account_id, hostname)
);
CREATE TABLE projects (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    site_id UUID NOT NULL REFERENCES sites(id) ON DELETE CASCADE,
    name VARCHAR(200) NOT NULL,
    billing_reference VARCHAR(128),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT projects_account_name_unique UNIQUE(account_id, name)
);
CREATE INDEX projects_account_created_idx ON projects(account_id, created_at DESC);
