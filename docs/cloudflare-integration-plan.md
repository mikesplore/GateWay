# Cloudflare integration plan (revision 4)

## Goal and boundary

Add Cloudflare edge enforcement as an optional adapter for sites already registered in GateWay. GateWay remains the source of truth for payment and entitlement. A verified payment, reversal, grace expiry, or admin action updates entitlement; a backend applier publishes the derived action to Cloudflare asynchronously. Customer requests do not call the GateWay API.

For v1, one GateWay deployment is configured with one operator-managed Cloudflare account and zone using deployment environment (`.env` for local development, environment/secret manager in production). The operator provisions credentials and selects the zone in configuration. Per-customer Cloudflare OAuth, arbitrary delegated accounts, and storing Cloudflare tokens in GateWay's database are out of scope for v1.

In scope: existing applications with hostnames on the configured Cloudflare zone, protected by a shared Worker and Workers KV.

Out of scope: Tunnel as a requirement, app builds/hosting on Cloudflare, rewriting DNS, and automatically taking over existing Workers or routes. Tunnel may be chosen by an operator, but GateWay does not require or manage it.

## Core flow

1. The configured zone has a proxied DNS record for each attached hostname. Cloudflare resolves that record to the site's existing public origin.
2. One shared Worker is deployed for the zone and one explicit Worker route is attached to each GateWay-managed hostname.
3. Each hostname has a KV record containing GateWay's desired enforcement action and monotonic version.
4. A durable GateWay outbox/applier writes KV only after the entitlement transaction commits. Payment webhooks and browser callbacks never write to Cloudflare directly.
5. For `proxy`, the Worker forwards the request to the origin selected by the hostname's Cloudflare DNS record. For `payment_page`, it returns static 402 HTML or JSON with `Cache-Control: no-store`.

Worker routes require an active zone and proxied DNS. [Cloudflare Worker routes](https://developers.cloudflare.com/workers/configuration/routing/routes/)

## Cloudflare credentials and prerequisites

The v1 Cloudflare token is supplied by the deployment operator in `.env` for development, or through the production secret manager. Never check it into source control, return it from an API, or log it. Do not store it in the database. Rotate it by updating deployment configuration and restarting/reloading GateWay.

Configuration should include the account ID, zone ID, KV namespace ID, Worker script name, and API token. The token needs only the API permissions required by the configured operations. If GateWay creates and removes per-host routes at runtime, it needs Workers Routes Edit as well as KV write access; if a separate bootstrap process owns routes, the runtime token can be limited to KV writes. Do not add DNS Edit in v1. Cloudflare permission scope and actual blast radius must be confirmed against the staging account before implementation. [API token permissions](https://developers.cloudflare.com/fundamentals/api/reference/permissions/)

**Paid Workers plan is a deployment prerequisite.** The Worker executes a KV read for requests and fails closed on missing/error state; free-plan quota exhaustion can therefore interrupt every affected site. GateWay should validate the configured account/namespace where API capabilities permit and expose a clear readiness error when required capabilities are absent. Document expected request volume and KV read cost. [Workers KV limits](https://developers.cloudflare.com/kv/platform/limits)

## KV and Worker state

KV key: `host:<normalized-hostname>`

Value: `{ "action": "proxy" | "payment_page", "version": <integer> }`

The origin is not duplicated in KV. The Worker uses the incoming hostname and Cloudflare's proxied DNS origin. Policy maps `active` and `grace` to `proxy`; `suspended` and `disabled_by_admin` to `payment_page`.

Worker behavior:

- Read KV with the minimum supported `cacheTtl` (currently documented as 30 seconds); avoid any additional isolate cache that can extend staleness beyond that bound.
- `proxy`: forward method, body, query, and required headers while preserving the public host semantics. Do not overwrite caller-controlled headers except the explicitly configured optional origin-authentication header.
- `payment_page`: return static 402 HTML or JSON and `Cache-Control: no-store`.
- Missing, invalid, or unreadable state returns 503. It must not become a free pass.
- Route-level failure and quota behavior must be demonstrated in the spike; Worker code returning 503 does not alone prove behavior when Cloudflare cannot execute the Worker.

## Honest enforcement guarantee and origin access

GateWay v1 enforces requests that pass through the Cloudflare Worker route. Tunnel is not needed for public origins reachable by Cloudflare and is not part of required setup.

If the origin accepts direct traffic, a person who knows its IP can bypass Cloudflare and reach the application without the Worker. Firewalling to Cloudflare's IP ranges alone is insufficient because other Cloudflare customers can proxy through those ranges. Therefore:

- Edge enforcement can be enabled without origin lockdown, but GateWay must label it `bypassable` and must not claim full-origin blocking.
- Strong origin enforcement requires the operator to restrict direct access using a mechanism the origin actually validates, such as an origin firewall plus per-site secret header validation in Nginx/application, or Authenticated Origin Pulls/mTLS.
- Secret-header injection is only secure if the origin rejects requests without the secret. GateWay does not assume all apps use Nginx and does not automatically modify customer apps or host firewalls.
- A probe is evidence, not proof of every bypass path. Store `verified`, `operator_acknowledged`, or `bypassable` separately from Worker apply status. Do not require Tunnel.

Cloudflare uses the hostname's DNS record to reach the origin, so the current Nginx-only upstream `127.0.0.1:5173` is not an origin Cloudflare can fetch. For Cloudflare attachment, use a publicly reachable origin (the common setup) or an operator-configured private connectivity option. Validate the Cloudflare DNS target where available and reject loopback, link-local, private, and CGNAT origins from the public-origin path. Do not reject them if the operator has explicitly configured and verified another reachable path. GateWay does not change DNS in v1.

## Propagation and product status

KV is eventually consistent. A successful REST write and read-back only confirm that the write was accepted/visible to the API path; they do not prove every Cloudflare location has observed it. KV's `cacheTtl` is a minimum cache duration, not a global convergence guarantee. Do not promise a hard 30- or 60-second maximum.

The UI/API reports distinct states:

- `queued`: desired state is durable in GateWay but not yet written.
- `write_confirmed`: Cloudflare accepted the version; global edge convergence is not asserted.
- `propagating`: write confirmed and within the empirically measured observation window.
- `applied`: use only if GateWay has a meaningful defined verification method; otherwise retain `write_confirmed` as the final truthful state.
- `apply_failed` / `enforcement_lost`: the write failed or required Worker/route/KV binding is missing.

Phase 0 measures propagation for suspend and resume from multiple locations. Use those observations to publish an expected range with no guaranteed upper bound. If the business later requires stronger freshness, evaluate Durable Objects or another strongly consistent service as a separate design; v1 deliberately uses KV.

## Ordering, idempotency, and reconciliation

Each site has a monotonic `state_version` incremented in the same database transaction as each entitlement change. The transaction also inserts an outbox event. The applier is the only runtime writer to GateWay-managed KV keys.

- Claim rows with database leases/locking so concurrent GateWay instances cannot write one site simultaneously.
- Collapse pending events to the highest desired version per site. Keep one in-flight write per hostname and respect KV's per-key write limit.
- KV has no compare-and-swap. No algorithm can stop a separate actor with the same credentials from overwriting a key. Enforce single-writer ownership operationally: only GateWay's applier writes managed keys, and bootstrap tooling must not mutate state keys.
- Before writing, read current KV state where practical. Never intentionally write a version lower than the latest GateWay desired/applied version. After write, read back and verify version; if a different version is present, mark drift and repair from the database's latest desired version.
- Treat read-back as API-path verification only, not global propagation proof. Never downgrade the site's desired version based on stale edge/cache observations.
- Apply only on state change; no per-request writes or heartbeat writes. Retry 429 and 5xx with bounded exponential backoff/jitter and honor `Retry-After`. Do not poll Cloudflare REST per request.
- Reconcile route/Worker bindings on startup and periodically. List managed metadata in bulk where possible; compare desired resources and repair only resources GateWay owns. Report missing/deleted bindings as `enforcement_lost`.

KV allows one write per second per key. [KV write limits](https://developers.cloudflare.com/kv/api/write-key-value-pairs/)

## Routes and least privilege

Use explicit per-host routes for v1 if the staging token confirms it is feasible. The bootstrap step creates the Worker and KV namespace. The runtime deployment token needs KV write and route read/verification; route create/delete should use a separate operator/bootstrap credential if Cloudflare permissions allow that split. If attach/detach must be automated, the runtime token also needs route write permission; disclose that permission to the operator and scope it to the selected zone where possible.

Do not request DNS write. Detect an existing route or Worker conflict and stop for explicit operator action. Never overwrite or delete resources unless GateWay can prove ownership. The default documented route limit is 1,000 per zone; route-per-site is for modest scale. [Workers platform limits](https://developers.cloudflare.com/workers/platform/limits/)

## Persistence and architecture

Entitlement stays independent from enforcement. One entitlement may feed Nginx, Cloudflare, or both, with one hostname having one explicit owner for each enforcement path.

Replace/supplement the current single-target site apply fields with:

- `site_enforcement_targets`: site, driver (`nginx` or `cloudflare`), target reference, desired version/action, last write-confirmed version, truthful status, origin-lockdown status, error, last attempt/success.
- `cloudflare_target_config`: account/zone/namespace/Worker/route identifiers and ownership metadata. Credentials remain in deployment environment, not this table.
- `enforcement_outbox`: site, target, version, action, attempts, next-attempt time, lease owner/expiry.
- `state_version` on the site.

Extract `EnforcementDriver.apply(site, action, version)` and `reconcile(site)`. Nginx remains an adapter and moves to the same durable queue. Cloudflare SDK/API types remain in its adapter. A pure policy maps entitlement to action. Provider callbacks never call enforcement APIs.

## Operations and readiness

- Configuration docs list required Cloudflare `.env` values by name, with no example secrets.
- Readiness checks validate that Cloudflare is enabled only when token, account/zone/namespace IDs, Worker binding, paid-plan prerequisite acknowledgement, and required API capabilities are present.
- Expose per-target desired/applied version, queue age, write confirmation time, last error, resource drift, and origin bypass label.
- Manual retry/reconcile is idempotent; detach removes only GateWay-owned routes/bindings and leaves DNS untouched.
- Secrets are redacted from logs and responses. `.env` remains local and ignored; production uses the deployment's secret manager.

## Delivery sequence

### Phase 0: staging spike (gate for implementation)

- Configure a non-production account, paid Workers plan, zone, proxied DNS hostname, KV namespace, Worker, and test origin without Tunnel.
- Verify active proxy, suspended uncached 402 with zero origin hits, missing state 503, and actual route failure/quota behavior.
- Measure suspend/resume visibility from multiple regions and distinguish REST read-back from edge convergence; confirm no promised hard maximum.
- Verify public host/TLS, request body/method/query forwarding, and WebSocket behavior.
- Confirm API permission split for bootstrap/runtime and route management; detect an unrelated route without modifying it.
- Test KV per-key and global limits with bursts and applier retries.
- Demonstrate direct-origin bypass when unlocked. Then validate the documented optional origin-lockdown method against a real origin. Do not make Tunnel a test requirement.

### Phase 1: common enforcement foundations

- Add per-target status, site `state_version`, transactional outbox, leased claiming, retries, audit, and queue health.
- Extract entitlement-to-action policy and move Nginx onto the common queue while migrating its existing apply status.
- Add Cloudflare-origin validation that distinguishes public origin from explicitly configured alternate reachability.

### Phase 2: Cloudflare adapter

- Add `.env`/deployment configuration and startup capability checks; do not persist runtime token in the database.
- Add bootstrap instructions/tooling for Worker, KV, and route ownership.
- Implement the KV applier, route conflict inspection, optional route lifecycle, backoff, read-back verification, and reconciliation.
- Add operations endpoints and frontend setup/status flow.
- Report `write_confirmed`/`propagating` honestly; do not invent globally applied confirmation.

### Phase 3: pilot

- Attach one low-risk proxied public-origin site in a staging zone; run active, suspend, resume, missing-state, route deletion, and API failure scenarios.
- Deliver duplicate/out-of-order entitlement events; verify leased serialization and latest desired version repair.
- Measure propagation; test origin direct bypass and optional lockdown.
- Pilot production only after the operator reviews expected KV delay, plan/cost, bypass status, and failure behavior.

Cloudflare-hosted app deployment remains a separate future project with its own hosting provider interface.

## Acceptance criteria

- A verified entitlement change durably queues Cloudflare work and retries after temporary API errors.
- A delayed or duplicate event cannot intentionally overwrite a newer version; external writes are detected and repaired from GateWay's current desired state.
- KV write limits are respected and 429 retries lose no events.
- Active/grace traffic proxies by the hostname's Cloudflare DNS; suspended/admin-disabled traffic gets uncached 402 without contacting the origin.
- Missing state, KV failure, Worker error, and quota behavior are fail-closed as verified by the spike.
- UI distinguishes write accepted from global propagation; no hard convergence bound is claimed.
- Tunnel is never required. Public origins work without it; unreachable private origins need an explicitly configured connectivity path.
- Sites without origin lockdown are visibly `bypassable`; GateWay does not claim direct-origin blocking.
- Cloudflare token is supplied through deployment configuration, not stored in DB or returned by an API.
- Existing routes, Workers, and DNS are not changed without explicit operator action and ownership proof.
