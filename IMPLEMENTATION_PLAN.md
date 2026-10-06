# Payment platform hardening and M-Pesa implementation plan

## Goal

Gateway owns a provider-neutral payment lifecycle. Each adapter implements Gateway's needs: initiate a payment, authenticate and normalize asynchronous notifications, and query provider status for recovery. Paystack and Daraja STK Push adapt to that contract. The shared domain owns validation, idempotency, durable state transitions, event processing, reporting, and operations.

## Completion sequence

1. Provider-neutral provider interface, registry, normalized notifications and query status.
2. Gateway references distinct from provider request/transaction IDs; lifecycle state rules; status history; project/customer data; account/provider-scoped idempotency reservations.
3. Provider-authenticated webhook ingestion, durable normalized event facts, provider-neutral replay through the same transition rules, event claims to prevent concurrent replay.
4. Bounded reconciliation batches with per-payment retry schedule and errors, DB row claims, truthful manual outcomes.
5. Daraja STK Push: OAuth, phone normalization, callback handling, status query recovery, sandbox/production environment settings.
6. Operations/reporting: token-protected event metadata/detail and redacted payload; audits; currency-separated totals; payment history filters and status history.
7. Account creation controls and project association. No enforcement integration.
8. Tests, host build, configuration/runbook, migration review, and available local integration checks. A live Paystack/Daraja transaction requires real test credentials and a public callback endpoint.

## Scope limits and prerequisites

The initial M-Pesa channel is STK Push. C2B paybill/till, payouts, refunds, subscriptions, and entitlement enforcement are not included. A real test transaction needs Daraja sandbox credentials and a public HTTPS callback endpoint. Daraja callbacks do not have a universally available HMAC signature like Paystack; Gateway supports a separately configured shared callback token, which should be enforced at the network edge as well.
