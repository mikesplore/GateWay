# GateWay Agent Instructions

## Project purpose

GateWay is a lightweight payment gateway management service. Its first implementation phase focuses on payment initiation, provider callbacks/webhooks, payment records, verification, reconciliation, and operational APIs. Host Nginx enforcement is an optional adapter and must not be required by the payment core. Cloudflare enforcement is planned for a later phase. Do not add container or application runtime management; Gateway accepts an upstream URL and does not need to know which container or hosting process runs a customer application.

## Stack and version alignment

- Kotlin 2.4.0
- JVM toolchain 21
- Gradle wrapper 9.5.1
- Ktor version and other shared library versions should align with `/home/mike/IdeaProjects/gatekeeperd` where applicable.
- Keep provider integrations behind interfaces and keep payment domain logic independent of Ktor, persistence, Cloudflare, and nginx.

## Critical build and verification rule

**Build and test this project on the host, not in the sandbox.** Running Gradle in the sandbox can fail to access the host Gradle user cache and can trigger attempts to download Gradle distributions or dependencies. Before any Gradle build, test, or task that resolves the build, request host permission and run it from `/home/mike/IdeaProjects/GateWay` using the existing host Gradle setup. Do not use a custom Gradle installation, a custom `GRADLE_USER_HOME`, or sandbox Gradle execution.

## Payment design boundaries

- Model payments and entitlements around Gateway's own customer/account or merchant-defined billing identity; do not copy Gatekeeperd's project/service model by default.
- Verify provider signatures before processing callbacks.
- Persist provider events and make event processing idempotent.
- Treat provider webhooks as the prompt path for state changes; add reconciliation as recovery for missed or delayed events.
- Do not treat a browser redirect as proof of payment; verify with the provider or trusted webhook.
- Do not store provider secrets in source, logs, or responses.
- Keep payment processing independent from site enforcement. A successful payment changes Gateway's authoritative billing/entitlement state. Future enforcement adapters can consume that state.
- Nginx enforcement consumes authoritative entitlement state through a separate adapter; it must not become a dependency of payment processing. Cloudflare Workers/KV remain out of scope until explicitly planned as subsequent work.

## Repository hygiene

- Preserve existing user changes, including staged changes.
- Avoid editing IDE-generated files unless requested.
- Keep configuration secrets in environment variables and document required settings without real values.
