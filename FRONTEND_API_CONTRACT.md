# GateWay frontend API contract

**Status:** frontend-facing contract for the Nuxt dashboard. Payment, project, site, operations, and human login/session routes below reflect the current Ktor API. The dashboard must not embed merchant API keys or `GATEWAY_OPS_TOKEN` in browser code.

## API conventions

- Base URL: configurable at frontend deployment, e.g. `NUXT_PUBLIC_GATEWAY_API_BASE` (no trailing slash). Local default may be `http://localhost:8080`.
- JSON request and response bodies use `Content-Type: application/json`.
- Timestamps are ISO-8601 UTC strings. UUIDs are strings. Monetary amounts are decimal strings, never floating-point JSON numbers.
- Errors use `{ "code": "...", "message": "..." }` unless noted. The frontend should show a safe message and retain the error code for diagnostics.
- Authenticated merchant routes accept `Authorization: Bearer <merchant-api-key>` for integrations or a valid `gateway_session` cookie for the dashboard. Ops routes accept the configured ops bearer for command-line clients or the session cookie for owner/operator users. The frontend must not embed those bearer credentials; account/operator scope is derived from the human session.
- No endpoint accepts an account ID as authorization. Account/project/site/payment scope is determined from the authenticated principal.

## Identity, login, and operator provisioning

### Provisioning policy

There is no public registration endpoint. The first operator is provisioned by an explicit startup bootstrap configuration or a one-time command run against the deployed application. Additional users are created only by an authenticated operator. Bootstrap credentials must be supplied via environment/secret manager or secure stdin, hashed before persistence, never logged, and bootstrap must become unavailable after the initial operator exists. No default password is permitted.

The existing `POST /api/accounts` creates a **merchant account and a one-time merchant API key**; it does not create a human login. It is disabled by default with `ACCOUNT_CREATION_MODE=disabled` and can be explicitly enabled with `ACCOUNT_CREATION_MODE=open`. Keep this separate from operator provisioning.

### Dashboard auth endpoints (implemented)

All endpoints are same-origin through a frontend server proxy/BFF where practical. The session cookie is `HttpOnly`, `SameSite=Strict`, and `Secure` by default; set `AUTH_COOKIE_SECURE=false` only for local plain-HTTP development. Do not expose session tokens to JavaScript or local storage.

#### `POST /api/auth/login` — implemented

Request:

```json
{ "email": "operator@example.com", "password": "..." }
```

Success `200` (sets session cookie):

```json
{
  "user": {
    "id": "uuid",
    "email": "operator@example.com",
    "displayName": "Operator",
    "role": "owner",
    "accountId": "uuid"
  },
  "expiresAt": "2026-10-06T12:00:00Z"
}
```

Invalid credentials: `401 { "code": "invalid_credentials", "message": "Email or password is incorrect" }`. Use the same response for unknown email and wrong password. Rate-limit attempts and audit successful/failed login without recording passwords or tokens.

#### `GET /api/auth/session` — implemented

Returns the current `{ "user": ..., "expiresAt": "..." }`; returns `401` when unauthenticated. Used at app startup and page refresh.

#### `POST /api/auth/logout` — implemented

Revokes current session, clears cookie, returns `204` (or `{ "status": "logged_out" }`).

#### `POST /api/auth/accept-invite` — implemented

Request: `{ "token": "one-time-invite-token", "password": "at-least-12-characters" }`. Stores the password hash and consumes the invite. Returns user metadata; expired, unknown, or consumed invite returns `409`.

#### `POST /api/ops/users` — implemented, owner only

Creates an operator; never public. Request: `{ "email": "...", "displayName": "...", "role": "operator" }`. The response contains user metadata and a one-time invite token valid for 24 hours; never a generated plaintext password. Initial implementation supports `owner` and `operator`, both account-scoped and authorized for operations APIs.

### Bootstrap (implemented at startup)

Set `GATEWAY_BOOTSTRAP_OWNER_EMAIL` and `GATEWAY_BOOTSTRAP_OWNER_PASSWORD` (12+ characters), optionally `GATEWAY_BOOTSTRAP_OWNER_NAME` and `GATEWAY_BOOTSTRAP_ACCOUNT_NAME`, before starting the app. It atomically creates the first account and owner only if none exists. There is no CLI bootstrap command and no `POST /api/auth/register`.

## Current backend routes

Auth notation: **M** = merchant API key; **O** = ops token; **S** = human session cookie; **Public** = no human session.

### Health

| Method and path | Auth | Response / behavior |
|---|---|---|
| `GET /api/health` | Public | `{ "status": "ok" }` liveness |
| `GET /api/ready` | Public | `ready` plus DB/Nginx readiness; `503` if a required dependency is unavailable |

### Merchant dashboard data

For the browser dashboard, these routes accept **S** and scope results to the user's account; integrations may continue to use **M**.

| Method and path | Request/query | Response |
|---|---|---|
| `GET /api/payments` | `status`, `provider`, `currency`, `projectId`, `from`, `to`, `limit`, `offset` | Array of payment records |
| `GET /api/payments/summary` | — | `{count,succeeded,pending,failed,reversed,amountsByCurrency,succeededAmountsByCurrency}` |
| `GET /api/payments/{reference}` | — | Payment record |
| `GET /api/payments/{reference}/history` | — | Array of `{previousStatus,status,source,providerTransactionId,occurredAt}` |
| `POST /api/payments/{reference}/reconcile` | — | `{status,payment}`; statuses include `reconciled`, `unchanged`, `provider_unavailable`, `still_pending` |
| `GET /api/projects` | — | Array of `{id,siteId,name,billingReference,createdAt,status,statusReason}` |
| `POST /api/projects` | `{name,siteId?,billingReference?}` | `201` project record |
| `GET /api/projects/{projectId}` | — | Project with linked sites, customers found through payments, payment totals, and recent payments |
| `PATCH /api/projects/{projectId}` | `{status,reason?}` | Set `active`, `suspended`, or `archived`; manual suspension survives successful payment callbacks; archive retains history and blocks new payments |
| `GET /api/customers` | — | Account-scoped customer records |
| `GET /api/customers/{customerId}` | — | Customer, linked projects, and payment history |
| `PATCH /api/customers/{customerId}` | `{displayName}` | Update customer display name |
| `GET /api/sites` | — | Array of site records |
| `POST /api/sites` | `{hostname,port,projectId}` | `201` site record; derives `http://127.0.0.1:{port}`, uses hostname for TLS, and queues Nginx apply (requires Nginx enabled) |
| `PUT /api/sites/{siteId}` | `{hostname,upstreamUrl?,tlsRef?,template?,projectId?}` | Updated site record; queues enforcement apply |

Payments store account-scoped `customerId` and optional `projectId` independently. Email/phone values identify or enrich the account customer record; customers are not owned by projects. Customer records are backfilled from existing payment contact data when migration V8 runs.

Current payment response shape:

```json
{
  "id": "uuid",
  "provider": "paystack",
  "reference": "gateway-reference",
  "amount": "250.00",
  "currency": "KES",
  "status": "pending",
  "checkoutUrl": "https://...",
  "projectId": "uuid",
  "customerId": "uuid",
  "customerEmail": "payer@example.com",
  "customerPhone": null
}
```

Current site response shape:

```json
{
  "id": "uuid",
  "hostname": "app.example.com",
  "createdAt": "2026-10-06T11:00:00Z",
  "projectId": "uuid",
  "upstreamUrl": "http://127.0.0.1:5173",
  "tlsRef": "app.example.com",
  "template": "proxy",
  "entitlementState": "active",
  "stateReason": "payment_succeeded",
  "stateChangedAt": "2026-10-06T11:00:00Z",
  "stateEffectiveAt": null,
  "appliedHash": "sha256-or-null",
  "applyStatus": "applied",
  "lastApplyError": null
}
```

### Merchant credential administration

These are current API-key endpoints for programmatic integrations. Do not expose the merchant key in dashboard JavaScript. They can be surfaced in a UI only after the backend accepts **S** and the user is authorized to manage integration credentials.

| Method and path | Request | Response |
|---|---|---|
| `GET /api/account/api-keys` | — | Key metadata, no secrets |
| `POST /api/account/api-keys` | `{name}` | Key metadata and `secret` exactly once |
| `POST /api/account/api-keys/{keyId}/rotate` | `{name}` | Replacement metadata and `secret` exactly once |
| `POST /api/account/api-keys/{keyId}/revoke` | — | `{ "status": "revoked" }` |

### Operations screens

These accept **O** for command-line clients or **S** for owner/operator users. The UI must not contain the shared ops token. Operator invites and operations actions are audited where applicable.

| Method and path | Auth now / required for UI | Request/query | Response |
|---|---|---|---|
| `GET /api/ops/payment-events` | O / S | `status=received\|processed\|failed`, `limit`, `offset` | Event metadata list |
| `GET /api/ops/payment-events/{eventId}` | O / S | — | Event details with redacted payload |
| `POST /api/ops/payment-events/{eventId}/replay` | O / S | — | `{ "status": "replayed" }`; conflict if not replayable |
| `GET /api/ops/nginx/configs` | O / S | — | Enabled state, external config inspections, orphan files |
| `POST /api/ops/nginx/reconcile` | O / S | — | Reconciliation queue counts/status |
| `POST /api/ops/nginx/sites/{siteId}/apply` | O / S | — | `{ "status": "queued\|disabled" }` |
| `PUT /api/ops/sites/{siteId}/entitlement` | O / S | `{state,reason,effectiveAt?}` | Updated site record; states: `active`, `grace`, `suspended`, `disabled_by_admin` |

### Provider callbacks (server-to-server; not dashboard actions)

| Method and path | Auth/verification | Purpose |
|---|---|---|
| `POST /api/payments/paystack/webhook` | Paystack signature | Persist/dedupe provider notification and apply valid payment status |
| `POST /api/payments/mpesa/callback` | Configured callback token | Persist/dedupe Daraja result callback |
| `GET /api/payments/paystack/callback` | Provider verification before state change | Browser return; returns verified/current payment record, not proof by redirect alone |

### Account creation (current behavior; not human registration)

`POST /api/accounts` accepts `{name,email?}` and returns `{accountId,name,email,apiKey}` once when `ACCOUNT_CREATION_MODE=open`; it returns `403` when disabled. This is merchant-account provisioning with a secret API key, not dashboard user signup. No public human-user registration route is part of this contract.

## Remaining frontend/backend integration considerations

1. Decide whether each deployment has one merchant account or supports separately provisioned accounts. Current invited users belong to the bootstrapped account.
2. Add pagination metadata when practical. Current list endpoints return arrays and use `limit`/`offset`; document any maximums and return total/next cursor if the UI needs accurate paging.
3. Keep the dashboard same-origin or add CORS only for explicitly configured origins if the browser calls Ktor directly. A same-origin Nuxt server proxy/BFF centralizes cookie policy.
4. Add request IDs and ensure logs never include API keys, passwords, session cookies, provider secrets, invite tokens, or unredacted provider payloads.

## Dashboard initial screens supported by this contract

- Sign in (`/api/auth/login`), session restore (`/api/auth/session`), and logout (`/api/auth/logout`).
- Overview: summary totals and recent payments.
- Payments list, filters, details, status history, manual reconcile.
- Project detail and lifecycle actions, linked sites, customers discovered through payments, and project payment totals.
- Account-wide customer profiles linked to projects only through their payments.
- Operations events/replay and Nginx drift/apply controls, gated by operator role.
- API keys/settings only after session-authenticated key-management is implemented.
