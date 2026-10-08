ALTER TABLE projects
    ADD COLUMN status VARCHAR(24) NOT NULL DEFAULT 'active',
    ADD COLUMN status_reason VARCHAR(256);

ALTER TABLE projects
    ADD CONSTRAINT projects_status_check CHECK (status IN ('active', 'suspended', 'archived'));

CREATE TABLE customers (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    display_name VARCHAR(200),
    email VARCHAR(320),
    phone_number VARCHAR(24),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE INDEX customers_account_created_idx ON customers(account_id, created_at DESC);
CREATE INDEX customers_account_email_idx ON customers(account_id, lower(email)) WHERE email IS NOT NULL;
CREATE INDEX customers_account_phone_idx ON customers(account_id, phone_number) WHERE phone_number IS NOT NULL;

ALTER TABLE payments ADD COLUMN customer_id UUID REFERENCES customers(id) ON DELETE SET NULL;
CREATE INDEX payments_customer_created_idx ON payments(customer_id, created_at DESC);

WITH customer_contacts AS (
    SELECT DISTINCT ON (account_id, COALESCE(NULLIF(lower(trim(request_email)), ''), 'phone:' || customer_phone))
        account_id,
        NULLIF(lower(trim(request_email)), '') AS email,
        customer_phone AS phone_number,
        min(created_at) OVER (PARTITION BY account_id, COALESCE(NULLIF(lower(trim(request_email)), ''), 'phone:' || customer_phone)) AS created_at
    FROM payments
    WHERE NULLIF(trim(request_email), '') IS NOT NULL OR customer_phone IS NOT NULL
    ORDER BY account_id, COALESCE(NULLIF(lower(trim(request_email)), ''), 'phone:' || customer_phone), created_at
)
INSERT INTO customers (id, account_id, email, phone_number, created_at, updated_at)
SELECT gen_random_uuid(), account_id, email, phone_number, created_at, created_at
FROM customer_contacts;

UPDATE payments p
SET customer_id = c.id
FROM customers c
WHERE c.account_id = p.account_id
  AND ((NULLIF(trim(p.request_email), '') IS NOT NULL AND lower(trim(p.request_email)) = c.email)
    OR (NULLIF(trim(p.request_email), '') IS NULL AND p.customer_phone IS NOT NULL AND p.customer_phone = c.phone_number));
