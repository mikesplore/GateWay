# GateWay

GateWay is a lightweight payment processing and optional host-Nginx enforcement service. It provides merchant accounts, Paystack and M-Pesa STK Push initiation, provider notifications and verification, PostgreSQL payment/event records, reconciliation, operations APIs, account-scoped payment history, projects that group sites, and entitlement-driven Nginx configs. Cloudflare enforcement is a later adapter. GateWay accepts upstream URLs and does not manage customer containers or hosting runtimes.

## Stack

- Kotlin 2.4.0, JVM 21, Gradle 9.5.1
- Ktor 3.5.0
- PostgreSQL with Flyway migrations and Exposed persistence

## Configuration

Set `DB_URL`, `DB_USER`, and `DB_PASSWORD` for PostgreSQL. Set `PAYSTACK_SECRET_KEY` to enable Paystack transactions and webhook validation. For M-Pesa, set `MPESA_ENVIRONMENT` (`sandbox` or `production`), `MPESA_CONSUMER_KEY`, `MPESA_CONSUMER_SECRET`, and a high-entropy `MPESA_CALLBACK_TOKEN`. When `MPESA_ENVIRONMENT=sandbox`, Gateway defaults to Safaricom's shared Express sandbox shortcode and passkey (`174379` and the published sandbox passkey); set `MPESA_SHORTCODE` and `MPESA_PASSKEY` explicitly to override them. Production requires the shortcode and passkey associated with the live merchant account. `PORT` defaults to `8080`; `GATEWAY_PUBLIC_URL` must be publicly reachable over HTTPS for provider callbacks in a deployed setup.

Configure browser CORS with `CORS_ALLOWED_ORIGINS`, a comma-separated list of exact origins including scheme and optional port (for example, `https://dashboard.example.com`). It defaults to `http://localhost:3000,http://localhost:5173` for local frontend development. Credentials are enabled for cookie-based sessions; do not use `*` as an origin.

Nginx enforcement is disabled by default. See [docs/nginx-enforcement.md](docs/nginx-enforcement.md) for host include, directory and sudo setup, TLS helper contract, project/site flow, migration, and operations APIs. Enable with `NGINX_ENABLED=true` only after configuring the host. The payment core works without Nginx.

### Dashboard login and initial owner

GateWay has no public human-user registration endpoint. To provision the first owner, set `GATEWAY_BOOTSTRAP_OWNER_EMAIL`, `GATEWAY_BOOTSTRAP_OWNER_PASSWORD` (at least 12 characters), and optionally `GATEWAY_BOOTSTRAP_OWNER_NAME` and `GATEWAY_BOOTSTRAP_ACCOUNT_NAME` in the process environment or secret manager before startup. On startup, if no owner exists, GateWay atomically creates the initial account and owner. Later startups do not recreate or reset the owner. Remove the bootstrap secrets from the runtime environment after the first successful start. If bootstrap is attempted after an owner exists, it is a no-op.

`POST /api/auth/login`, `GET /api/auth/session`, and `POST /api/auth/logout` provide cookie-based dashboard sessions. The cookie is `HttpOnly`, `SameSite=Strict`, expires after 12 hours, and is `Secure` by default. For local plain-HTTP development only, set `AUTH_COOKIE_SECURE=false`; keep it enabled behind HTTPS in deployment. `POST /api/ops/users` lets an owner invite an operator; the response contains a one-time invite token valid for 24 hours. The invitee sets a password with `POST /api/auth/accept-invite`. Passwords are PBKDF2-HMAC-SHA256 hashed, and session/invite tokens are stored only as hashes. Login attempts are rate-limited per remote address in each running process.

Dashboard requests may use the session cookie in place of a merchant API key or shared operations token. Human sessions are scoped to their account; only owners and operators may access account APIs, and operations APIs require the owner/operator role. Keep the dashboard same-origin or proxy API calls through its server runtime; do not place `GATEWAY_OPS_TOKEN` or merchant API keys in browser bundles. The existing `POST /api/accounts` is merchant-account/API-key provisioning, not human signup, and is disabled by default (`ACCOUNT_CREATION_MODE=disabled`).

Do not run Gradle builds in the sandbox. Build and test GateWay on the host using the existing Gradle cache and ask for host permission before running Gradle.

## Initial API

`POST /api/accounts` creates a merchant account and returns an initial API key once. Store that key securely; GateWay stores only its SHA-256 hash.

Use the merchant key as `Authorization: Bearer <merchant-api-key>` for payment and key-management APIs. `GET /api/account/api-keys` lists key names, prefixes, and usage/revocation timestamps without returning secrets. `POST /api/account/api-keys` creates an additional named key and returns its secret once. `POST /api/account/api-keys/{keyId}/revoke` revokes an active key. `POST /api/account/api-keys/{keyId}/rotate` atomically revokes the selected key and returns a replacement secret once. Keep a second active key available while rotating so the rotation request's credential remains valid.

`POST /api/payments` requires `Authorization: Bearer <merchant-api-key>` and a body such as:

```json
{
  "email": "payer@example.com",
  "amount": "250.00",
  "currency": "KES"
}
```

Choose a provider with `provider`. Paystack remains the default and uses email. M-Pesa STK Push uses a Kenyan phone number and KES:

```json
{
  "provider": "mpesa",
  "phoneNumber": "0712345678",
  "amount": "250",
  "currency": "KES",
  "projectId": "optional-project-uuid",
  "idempotencyKey": "order-123"
}
```

The response reference is generated by Gateway. Provider transaction and request IDs are retained internally. M-Pesa initiation returns no browser checkout URL; Daraja sends an STK prompt to the phone. STK Push currently accepts whole KES amounts. Gateway passes the configured callback token in the Daraja callback URL query, and validates it before parsing callback data. `POST /api/payments/mpesa/callback` accepts the Daraja result callback. A query/verification adapter checks pending transactions through Daraja's STK query endpoint.

The response includes the payment reference, pending status, and Paystack checkout URL. `GET /api/payments/{reference}` returns the account's payment record and requires the same API key.

Configure Paystack to send transaction events to `POST /api/payments/paystack/webhook`. The endpoint verifies `x-paystack-signature` against the exact raw request body before persisting or applying an event.

Paystack's browser return URL is `GET /api/payments/paystack/callback`. The callback verifies the reference directly with Paystack, applies a matching successful result idempotently, then returns the current payment status. The webhook remains the normal event path.

`GET /api/health` is a liveness endpoint; `GET /api/ready` checks PostgreSQL and, when enabled, Nginx managed-directory readiness. The database schema is applied automatically through Flyway during startup.

## Payments, operations, and merchant records

Authenticated `GET /api/payments?status=pending&provider=mpesa&currency=KES&projectId=...&from=...&to=...&limit=50&offset=0` lists filtered history. `GET /api/payments/summary` returns counts and gross amounts grouped by currency, plus succeeded amounts by currency. `GET /api/payments/{reference}/history` returns status changes. `POST /api/payments/{reference}/reconcile` asks the stored provider to verify a pending payment. Background reconciliation checks due pending payments in bounded batches, claims rows under database locks, and retries with exponential backoff; configure `RECONCILIATION_INTERVAL_SECONDS`, `RECONCILIATION_STALE_MINUTES`, `RECONCILIATION_BATCH_SIZE`, or disable the in-process worker with `RECONCILIATION_ENABLED=false` when running an external worker.

Operations APIs use `Authorization: Bearer <GATEWAY_OPS_TOKEN>`: `GET /api/ops/payment-events?status=failed` inspects event metadata, `GET /api/ops/payment-events/{eventId}` returns a redacted payload for troubleshooting, and `POST /api/ops/payment-events/{eventId}/replay` retries through the event's owning provider adapter. Operator inspections and replay attempts are audited.

Merchants create projects with `POST /api/projects` (`{"name":"My project","billingReference":"customer-123"}`), then create sites with `hostname`, local `port`, and required `projectId`. GateWay derives `http://127.0.0.1:{port}`, requests a TLS certificate with Certbot when one is missing, and queues the Nginx configuration. Nginx enforcement must be enabled; the domain must resolve to this host and allow inbound HTTP for certificate validation. `GET /api/projects/{id}` provides project details, associated sites, payment totals, and customers found through project payments. Projects can be manually suspended, reactivated, or archived; manual suspension is not undone by successful payment events, and archive retains history while blocking new payments. Customers are account-scoped, linked to payments by `customerId`, and are independent of projects; the same customer may pay across multiple projects. Customer contact records are backfilled from existing payment data in migration V8. See the [Nginx enforcement guide](docs/nginx-enforcement.md) for setup and migration.

`POST /api/accounts` can be enabled with `ACCOUNT_CREATION_MODE=open` for explicitly requested development scenarios; keep it disabled in deployment. `GATEWAY_OPS_TOKEN` remains available for command-line operations clients. For Paystack tests, set a test secret and configure its webhook. For Daraja sandbox checks, use your app's consumer credentials, a reachable HTTPS callback URL, and set a random token in `MPESA_CALLBACK_TOKEN`; also restrict callback ingress at the deployment edge. Live-provider integration still requires live shortcode/passkey credentials and a deployed public callback endpoint.
