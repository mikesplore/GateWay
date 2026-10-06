ALTER TABLE payments ADD COLUMN project_id UUID REFERENCES projects(id) ON DELETE SET NULL;
ALTER TABLE payments ADD COLUMN gateway_reference VARCHAR(128);
UPDATE payments SET gateway_reference = provider_reference WHERE gateway_reference IS NULL;
ALTER TABLE payments ALTER COLUMN gateway_reference SET NOT NULL;
CREATE UNIQUE INDEX payments_gateway_reference_unique ON payments(gateway_reference);
ALTER TABLE payments ADD COLUMN customer_phone VARCHAR(24);
ALTER TABLE payments ADD COLUMN description VARCHAR(256);
ALTER TABLE payments ADD COLUMN provider_request_id VARCHAR(128);
ALTER TABLE payments ADD COLUMN provider_transaction_id VARCHAR(128);
ALTER TABLE payments ADD COLUMN reconciliation_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE payments ADD COLUMN next_reconciliation_at TIMESTAMP;
ALTER TABLE payments ADD COLUMN reconciliation_claimed_at TIMESTAMP;
ALTER TABLE payments ADD COLUMN last_provider_error TEXT;
ALTER TABLE payments DROP CONSTRAINT IF EXISTS payments_status_check;
ALTER TABLE payments DROP CONSTRAINT IF EXISTS payments_status_v2_check;
ALTER TABLE payments ADD CONSTRAINT payments_status_v2_check CHECK (status IN ('initializing', 'pending', 'succeeded', 'failed', 'reversed', 'expired'));
CREATE INDEX payments_reconciliation_idx ON payments(status, next_reconciliation_at, updated_at);
CREATE INDEX payments_project_created_idx ON payments(project_id, created_at DESC);
ALTER TABLE payment_events ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE payment_events ADD COLUMN claimed_at TIMESTAMP;
ALTER TABLE payment_events ADD COLUMN normalized_status VARCHAR(24);
ALTER TABLE payment_events ADD COLUMN normalized_amount NUMERIC(14, 2);
ALTER TABLE payment_events ADD COLUMN normalized_currency VARCHAR(3);
ALTER TABLE payment_events ADD COLUMN provider_transaction_id VARCHAR(128);

CREATE TABLE payment_status_history (
    id UUID PRIMARY KEY,
    payment_id UUID NOT NULL REFERENCES payments(id) ON DELETE CASCADE,
    previous_status VARCHAR(24),
    new_status VARCHAR(24) NOT NULL,
    source VARCHAR(64) NOT NULL,
    provider_transaction_id VARCHAR(128),
    occurred_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX payment_status_history_payment_idx ON payment_status_history(payment_id, occurred_at DESC);

CREATE TABLE operations_audit (
    id UUID PRIMARY KEY,
    action VARCHAR(100) NOT NULL,
    target_id VARCHAR(128),
    details TEXT,
    occurred_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
