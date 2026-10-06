CREATE TABLE merchant_api_keys (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    key_prefix VARCHAR(16) NOT NULL,
    key_hash VARCHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_used_at TIMESTAMP,
    revoked_at TIMESTAMP
);

CREATE INDEX merchant_api_keys_account_created_idx ON merchant_api_keys(account_id, created_at DESC);

-- Preserve existing credentials while moving authentication to the rotatable key table.
INSERT INTO merchant_api_keys (id, account_id, name, key_prefix, key_hash, created_at)
SELECT gen_random_uuid(), id, 'Migrated default key', 'gw_live_migrated', api_key_hash, created_at
FROM accounts
WHERE api_key_hash IS NOT NULL;

ALTER TABLE accounts DROP COLUMN api_key_hash;
