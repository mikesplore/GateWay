CREATE TABLE accounts (
    id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    email VARCHAR(320),
    api_key_hash VARCHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE payments (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts(id),
    provider VARCHAR(32) NOT NULL,
    provider_reference VARCHAR(128) NOT NULL,
    amount NUMERIC(14, 2) NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(24) NOT NULL CHECK (status IN ('pending', 'succeeded', 'failed', 'reversed')),
    checkout_url TEXT,
    idempotency_key VARCHAR(128) UNIQUE,
    request_email VARCHAR(320),
    paid_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT payments_provider_reference_unique UNIQUE(provider, provider_reference)
);

CREATE INDEX payments_account_created_idx ON payments(account_id, created_at DESC);
CREATE INDEX payments_status_created_idx ON payments(status, created_at);

CREATE TABLE payment_events (
    id UUID PRIMARY KEY,
    provider VARCHAR(32) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    deduplication_key VARCHAR(256) NOT NULL UNIQUE,
    provider_reference VARCHAR(128),
    raw_payload TEXT NOT NULL,
    status VARCHAR(24) NOT NULL CHECK (status IN ('received', 'processed', 'failed')),
    received_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMP,
    processing_error TEXT
);

CREATE INDEX payment_events_status_received_idx ON payment_events(status, received_at DESC);
