CREATE TABLE gateway_users (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    email VARCHAR(320) NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    role VARCHAR(32) NOT NULL CHECK (role IN ('owner', 'operator')),
    password_hash VARCHAR(256),
    invite_token_hash VARCHAR(64),
    invite_expires_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    disabled_at TIMESTAMP,
    CONSTRAINT gateway_users_email_unique UNIQUE (email),
    CONSTRAINT gateway_users_invite_hash_unique UNIQUE (invite_token_hash)
);

CREATE INDEX gateway_users_account_idx ON gateway_users(account_id, created_at);

CREATE TABLE gateway_user_sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES gateway_users(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    revoked_at TIMESTAMP,
    last_seen_at TIMESTAMP NOT NULL
);

CREATE INDEX gateway_user_sessions_user_idx ON gateway_user_sessions(user_id, expires_at);
