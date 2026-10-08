# Host Nginx enforcement

GateWay can optionally render host Nginx configs from its site registry and entitlement state. Payment processing does not require Nginx. GateWay owns only one generated file per site under `NGINX_MANAGED_DIRECTORY`; it does not edit the Nginx main config, application configs, Docker, or app runtimes.

## Host setup

Install and operate Nginx separately. Add this once inside the Nginx `http {}` block (usually `/etc/nginx/nginx.conf`):

```nginx
include /etc/nginx/gateway.d/*.conf;
```

Create the managed directory with write access for the GateWay service user and read access for Nginx's privileged master process. Keep the directory dedicated to GateWay; files named `<site-uuid>.conf` are generated and may be replaced.

GateWay runs `sudo -n /usr/sbin/nginx -t` before activation and `sudo -n /bin/systemctl reload nginx` after activation. A narrow sudoers rule for the service user can be:

```sudoers
gateway ALL=(root) NOPASSWD: /usr/sbin/nginx -t, /bin/systemctl reload nginx
```

Use the actual service account and executable paths on the host. Validate sudoers edits with `visudo`. Give the service user write access only to the managed directory; do not run the web service as root.

Set `NGINX_ENABLED=true` only after the include is active and the managed directory is writable. `GET /api/ready` checks the database and that directory. Nginx is disabled by default.

## TLS certificates

Set a site's `tlsRef` to the certificate directory name, usually its hostname. Active and suspended configs both listen with that certificate, so suspension keeps HTTPS valid. `NGINX_CERTIFICATE_DIRECTORY` defaults to `/etc/letsencrypt/live`.

GateWay checks for `fullchain.pem` and `privkey.pem` before applying a TLS site. If absent, it invokes Certbot's Nginx plugin automatically. Set `GATEWAY_CERTIFICATE_EMAIL` to associate an email with the certificate; when unset, Certbot is registered without an email. The domain must resolve to this host, Nginx must be running, and inbound HTTP validation must be reachable. An optional external certificate helper can override Certbot with `GATEWAY_CERTIFICATE_HELPER`. The helper contract is:

```text
<absolute-helper-path> ensure <certificate-domain> <email>
```

The helper must be idempotent, run non-interactively, and leave the two certificate files in the configured certificate directory. Grant sudo access only to the helper command form used by your deployment. Certbot renewals should be enabled through the host's normal Certbot timer.

## Site, project, and entitlement flow

Create a project as a billing grouping, then attach one or more sites using `projectId`. Site creation takes hostname, local application port, and project ID; GateWay derives `http://127.0.0.1:<port>` and uses the hostname for TLS. A site records its upstream, certificate reference, template, entitlement, and apply result. The only initial template is `proxy`.

```http
POST /api/projects
Authorization: Bearer <merchant-api-key>
Content-Type: application/json

{"name":"BubblesBath","billingReference":"customer-42"}
```

```http
POST /api/sites
Authorization: Bearer <merchant-api-key>
Content-Type: application/json

{"hostname":"wash.example.com","port":5173,"projectId":"<project-uuid>"}
```

New projects and sites begin suspended. Once an authenticated provider event or provider status query confirms a successful payment for an active project, its sites become active. A verified reversal suspends them again. An operator can suspend or reactivate a project through `PATCH /api/projects/{projectId}`; successful payments never reactivate a manually suspended project. Archiving retains payment history and prevents new payments. These transitions are transactional with the payment event and deduplicated through the provider event store. A successful payment currently grants indefinite active entitlement; recurring billing periods, plans, and amount-to-duration rules are not implemented. Operations can set `active`, `grace`, `suspended`, or `disabled_by_admin` through `PUT /api/ops/sites/{siteId}/entitlement`. A grace request must include a future ISO-8601 `effectiveAt`; a scheduler changes it to suspended at expiry. `disabled_by_admin` is not overridden by payment events.

Active and grace sites proxy to their upstream. Suspended and admin-disabled sites return a static `402` response with `Cache-Control: no-store`, HTML for browsers and JSON when `Accept: application/json` is requested. No request-time call to GateWay is made.

## Safe apply and migration

Site changes enter a single in-process worker. Repeated changes for a site coalesce. GateWay renders the whole config, hashes it, checks for drift, stages a candidate config, runs `nginx -t`, atomically replaces that site's file, and reloads Nginx. If validation or reload fails, GateWay restores that site's previous file and records `apply_failed`; the previous working Nginx configuration remains loaded. Other sites continue through the queue. A periodic reconciler compares rendered hashes to files and queues drift for repair. It reports orphaned files without deleting them.

`GET /api/ops/nginx/configs` provides an import aid: server names, proxy upstreams, certificate paths, and GateWay orphan files found in the configured inspection directories. It does not rewrite manual configs. For each migration:

1. Inspect the current config and record its hostname, upstream, TLS certificate, and special routing behavior.
2. Create/attach the GateWay site record and review its hostname, upstream, project, and TLS reference. While the old config is still active, GateWay rejects applying the competing hostname.
3. Ensure Gatekeeperd or any manual config will no longer own that hostname. Disable the old config before activating the GateWay config; never leave both active for one hostname.
4. Apply with `POST /api/ops/nginx/sites/{siteId}/apply`, then verify HTTP/HTTPS and upstream behavior before migrating another site.

GateWay checks active `sites-enabled` and `conf.d` files for exact and wildcard hostname conflicts and rejects overlapping owners. Resolve a conflict explicitly; do not rely on Nginx server-block ordering. Manual configs outside `NGINX_CONFLICT_DIRECTORIES` are not checked, so configure that list to cover every active include directory.

Operations APIs use `Authorization: Bearer <GATEWAY_OPS_TOKEN>`. `POST /api/ops/nginx/reconcile` queues drifted sites and reports orphan files. Apply status and errors are visible in account-scoped site responses. Keep one GateWay process per host Nginx instance so the serialized worker remains the only writer.
