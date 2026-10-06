ALTER TABLE sites ADD COLUMN upstream_url VARCHAR(2048);
ALTER TABLE sites ADD COLUMN tls_ref VARCHAR(253);
ALTER TABLE sites ADD COLUMN template VARCHAR(32) NOT NULL DEFAULT 'proxy';
ALTER TABLE sites ADD COLUMN entitlement_state VARCHAR(32) NOT NULL DEFAULT 'suspended';
ALTER TABLE sites ADD COLUMN state_reason VARCHAR(128) NOT NULL DEFAULT 'awaiting_payment';
ALTER TABLE sites ADD COLUMN state_changed_at TIMESTAMP;
ALTER TABLE sites ADD COLUMN state_effective_at TIMESTAMP;
ALTER TABLE sites ADD COLUMN applied_hash VARCHAR(64);
ALTER TABLE sites ADD COLUMN apply_status VARCHAR(32) NOT NULL DEFAULT 'not_configured';
ALTER TABLE sites ADD COLUMN last_apply_error TEXT;

UPDATE sites SET state_changed_at = created_at WHERE state_changed_at IS NULL;

ALTER TABLE sites ADD CONSTRAINT sites_template_check CHECK (template IN ('proxy'));
ALTER TABLE sites ADD CONSTRAINT sites_entitlement_state_check
    CHECK (entitlement_state IN ('active', 'grace', 'suspended', 'disabled_by_admin'));
ALTER TABLE sites ADD CONSTRAINT sites_apply_status_check
    CHECK (apply_status IN ('not_configured', 'queued', 'applied', 'apply_failed', 'disabled'));

CREATE INDEX sites_apply_status_idx ON sites(apply_status, created_at);
CREATE UNIQUE INDEX sites_hostname_global_unique ON sites (lower(hostname));
