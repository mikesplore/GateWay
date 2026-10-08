package com.gateway.payment.presentation

import com.gateway.config.GatewayConfig
import com.gateway.enforcement.adapter.HostNginxDriver
import com.gateway.enforcement.adapter.NginxApplyQueue
import com.gateway.enforcement.domain.SiteInput
import com.gateway.payment.data.ExposedPaymentStore
import com.gateway.payment.data.PaystackProvider
import com.gateway.payment.data.MpesaProvider
import com.gateway.payment.domain.EventResult
import com.gateway.payment.domain.InitiatePaymentCommand
import com.gateway.payment.domain.AccountService
import com.gateway.payment.domain.PaymentEventProcessor
import com.gateway.payment.domain.PaymentStatus
import com.gateway.payment.domain.PaymentReconciler
import com.gateway.payment.domain.PaymentProvider
import com.gateway.payment.domain.ProviderPaymentService
import com.gateway.payment.domain.ProviderEventReplayService
import com.gateway.payment.domain.PaymentSite
import com.gateway.payment.domain.PaymentProject
import com.gateway.plugins.DatabaseFactory
import com.gateway.payment.domain.ProviderPaymentEvent
import com.gateway.payment.domain.HumanAuthService
import com.gateway.payment.domain.GatewayUser
import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.request.receiveText
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import io.ktor.server.routing.patch
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.server.application.ApplicationStopped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val store = ExposedPaymentStore()
private val provider = PaystackProvider()
private val mpesaProvider = MpesaProvider()
private val providers: Map<String, PaymentProvider> = listOf(provider, mpesaProvider).associateBy { it.name }
private val paymentService = ProviderPaymentService(store, providers)
private val eventProcessor = PaymentEventProcessor(store)
private val accountService = AccountService(store)
private val humanAuth = HumanAuthService(store)
private val loginAttempts = ConcurrentHashMap<String, java.util.ArrayDeque<Long>>()
private val reconciler = PaymentReconciler(store, providers)
private val eventReplay = ProviderEventReplayService(store, providers)
private val nginxDriver = HostNginxDriver()

fun Application.configurePaymentRoutes() {
    val bootstrapEmail = GatewayConfig.bootstrapOwnerEmail
    val bootstrapPassword = GatewayConfig.bootstrapOwnerPassword
    if (bootstrapEmail.isNotBlank() || bootstrapPassword.isNotBlank()) {
        require(bootstrapEmail.isNotBlank() && bootstrapPassword.isNotBlank()) { "Both GATEWAY_BOOTSTRAP_OWNER_EMAIL and GATEWAY_BOOTSTRAP_OWNER_PASSWORD are required" }
        humanAuth.bootstrapOwner(bootstrapEmail, GatewayConfig.bootstrapOwnerName, bootstrapPassword, GatewayConfig.bootstrapAccountName)
    }
    val jobs = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val nginxQueue = NginxApplyQueue(store, nginxDriver, jobs)
    jobs.launch {
        runCatching { nginxQueue.expireGrace() }
        if (GatewayConfig.nginxEnabled) runCatching { nginxQueue.reconcile() }
    }
    if (System.getenv("RECONCILIATION_ENABLED")?.equals("false", true) != true) jobs.launch {
        while (isActive) {
            runCatching {
                val changed = reconciler.reconcile(java.time.Instant.now(), GatewayConfig.reconciliationBatchSize)
                if (changed > 0) nginxQueue.enqueueAll()
            }
            delay(com.gateway.config.GatewayConfig.reconciliationIntervalSeconds * 1000)
        }
    }
    jobs.launch {
        while (isActive) {
            runCatching { nginxQueue.expireGrace() }
            if (GatewayConfig.nginxEnabled) runCatching { nginxQueue.reconcile() }
            delay(60_000)
        }
    }
    monitor.subscribe(ApplicationStopped) { jobs.cancel() }
    routing {
        // These endpoints are called only from Gateway-managed Nginx locations.
        // Nginx's internal auth location strips browser headers and request bodies.
        get("/api/enforcement/auth") {
            val siteId = runCatching { UUID.fromString(call.request.queryParameters["siteId"]) }.getOrNull()
            val site = siteId?.let(store::findSite)
            if (site?.effectiveEntitlementState in setOf("active", "grace")) call.respond(HttpStatusCode.OK)
            else call.respond(HttpStatusCode.Forbidden)
        }
        get("/api/enforcement/paywall") {
            val siteId = runCatching { UUID.fromString(call.request.queryParameters["siteId"]) }.getOrNull()
            val site = siteId?.let(store::findSite)
            val hostname = site?.hostname ?: "this site"
            call.response.headers.append(io.ktor.http.HttpHeaders.CacheControl, "no-store")
            val acceptsJson = call.request.headers[io.ktor.http.HttpHeaders.Accept]
                ?.contains("application/json", ignoreCase = true) == true
            if (acceptsJson) {
                call.respondText(
                    "{\"error\":\"payment_required\",\"status\":402,\"site\":\"$hostname\"}",
                    ContentType.Application.Json,
                    HttpStatusCode.PaymentRequired
                )
            } else {
                val body = "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Payment required</title></head><body><main><h1>Payment required</h1><p>Access to $hostname is currently unavailable. Contact the site owner.</p></main></body></html>"
                call.respondText(body, ContentType.Text.Html, HttpStatusCode.PaymentRequired)
            }
        }
        post("/api/auth/login") {
            val request = runCatching { call.receive<LoginRequest>() }.getOrNull()
            if (request == null || request.password.isEmpty() || request.password.length > 256) { call.respond(HttpStatusCode.BadRequest, ApiError("invalid_request", "Email and password are required")); return@post }
            val remote = call.request.local.remoteHost
            val now = System.currentTimeMillis()
            if (loginAttempts.size > 10_000) loginAttempts.entries.removeIf { (_, history) -> synchronized(history) { history.isEmpty() || history.first < now - 60_000 } }
            val attempts = loginAttempts.computeIfAbsent(remote) { java.util.ArrayDeque() }
            val rateLimited = synchronized(attempts) {
                while (attempts.isNotEmpty() && attempts.first < now - 60_000) attempts.removeFirst()
                if (attempts.size >= 10) true else { attempts.addLast(now); false }
            }
            if (rateLimited) { call.respond(HttpStatusCode.TooManyRequests, ApiError("rate_limited", "Too many login attempts; try again shortly")); return@post }
            val issued = runCatching { humanAuth.login(request.email, request.password) }.getOrNull()
            if (issued == null) { call.respond(HttpStatusCode.Unauthorized, ApiError("invalid_credentials", "Email or password is incorrect")); return@post }
            synchronized(attempts) { attempts.clear() }
            val cookie = buildString {
                append("gateway_session=${issued.token}; Path=/; HttpOnly; SameSite=Strict; Max-Age=43200")
                if (GatewayConfig.authCookieSecure) append("; Secure")
            }
            call.response.headers.append(io.ktor.http.HttpHeaders.SetCookie, cookie)
            call.respond(LoginResponse(UserResponse.from(issued.user), issued.expiresAt.toString()))
        }
        post("/api/auth/logout") {
            val token = call.request.cookies["gateway_session"]
            humanAuth.logout(token)
            val cookie = "gateway_session=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0" + if (GatewayConfig.authCookieSecure) "; Secure" else ""
            call.response.headers.append(io.ktor.http.HttpHeaders.SetCookie, cookie)
            call.respond(HttpStatusCode.NoContent)
        }
        get("/api/auth/session") {
            val session = humanAuth.session(call.request.cookies["gateway_session"])
            if (session == null) call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Sign in is required"))
            else call.respond(LoginResponse(UserResponse.from(session.first), session.second.toString()))
        }
        post("/api/auth/accept-invite") {
            val request = runCatching { call.receive<AcceptInviteRequest>() }.getOrNull()
            if (request == null || request.token.isBlank()) { call.respond(HttpStatusCode.BadRequest, ApiError("invalid_request", "Invite token and password are required")); return@post }
            val user = runCatching { humanAuth.acceptInvite(request.token, request.password) }.getOrNull()
            if (user == null) call.respond(HttpStatusCode.Conflict, ApiError("invite_invalid", "Invite is invalid or expired"))
            else call.respond(UserResponse.from(user))
        }
        post("/api/ops/users") {
            val session = humanAuth.session(call.request.cookies["gateway_session"])
            val expectedHost = call.request.headers["Host"] ?: call.request.local.serverHost
            val origin = call.request.headers["Origin"]
            val sameHost = runCatching { java.net.URI(origin).authority.equals(expectedHost, ignoreCase = true) }.getOrDefault(false)
            if (!sameHost || session == null || session.first.role != "owner") { call.respond(HttpStatusCode.Forbidden, ApiError("forbidden", "Owner authorization is required")); return@post }
            val request = runCatching { call.receive<CreateUserRequest>() }.getOrNull()
            if (request == null) { call.respond(HttpStatusCode.BadRequest, ApiError("invalid_request", "Email, display name, and role are required")); return@post }
            val created = runCatching { humanAuth.invite(session.first.accountId, request.email, request.displayName, request.role) }.getOrElse {
                call.respond(HttpStatusCode.BadRequest, ApiError("invalid_user", it.message ?: "Unable to invite user")); return@post
            }
            store.audit("operator_invited", created.first.id.toString(), "role=${created.first.role};by=${session.first.id}")
            call.respond(HttpStatusCode.Created, CreatedInviteResponse(UserResponse.from(created.first), created.second, java.time.Instant.now().plusSeconds(86400).toString()))
        }
        get("/api/health") { call.respond(mapOf("status" to "ok")) }
        get("/api/ready") {
            val databaseReady = runCatching { DatabaseFactory.ready() }.getOrDefault(false)
            val nginxError = nginxDriver.readinessError()
            if (databaseReady && nginxError == null) call.respond(mapOf("status" to "ready", "database" to "ready", "nginx" to if (GatewayConfig.nginxEnabled) "ready" else "disabled"))
            else call.respond(HttpStatusCode.ServiceUnavailable, mapOf("status" to "not_ready", "database" to if (databaseReady) "ready" else "not_ready", "nginx" to (nginxError ?: "disabled")))
        }

        post("/api/accounts") {
            if (GatewayConfig.accountCreationMode == "disabled") { call.respond(HttpStatusCode.Forbidden, ApiError("account_creation_disabled", "Merchant account creation is disabled")); return@post }
            val request = runCatching { call.receive<CreateAccountRequest>() }.getOrElse {
                call.respond(HttpStatusCode.BadRequest, ApiError("invalid_request", "Invalid account request")); return@post
            }
            val created = runCatching { accountService.create(request.name, request.email) }.getOrElse {
                call.respond(HttpStatusCode.BadRequest, ApiError("invalid_account", it.message ?: "Invalid account")); return@post
            }
            call.respond(HttpStatusCode.Created, CreatedAccountResponse(created.account.id.toString(), created.account.name, created.account.email, created.apiKey))
        }

        get("/api/account/api-keys") {
            val account = authenticatedAccount(call.request.headers["Authorization"], humanAuth.session(call.request.cookies["gateway_session"])?.first)
            if (account == null) { call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "A valid merchant API key is required")); return@get }
            call.respond(accountService.listKeys(account.id).map(MerchantApiKeyResponse::from))
        }

        post("/api/account/api-keys") {
            val account = authenticatedAccount(call.request.headers["Authorization"], humanAuth.session(call.request.cookies["gateway_session"])?.first)
            if (account == null) { call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "A valid merchant API key is required")); return@post }
            val request = runCatching { call.receive<CreateApiKeyRequest>() }.getOrElse {
                call.respond(HttpStatusCode.BadRequest, ApiError("invalid_request", "Invalid API key request")); return@post
            }
            val created = runCatching { accountService.createKey(account.id, request.name) }.getOrElse {
                call.respond(HttpStatusCode.BadRequest, ApiError("invalid_api_key", it.message ?: "Invalid API key")); return@post
            }
            call.respond(HttpStatusCode.Created, CreatedApiKeyResponse(MerchantApiKeyResponse.from(created.key), created.secret))
        }

        post("/api/account/api-keys/{keyId}/revoke") {
            val account = authenticatedAccount(call.request.headers["Authorization"], humanAuth.session(call.request.cookies["gateway_session"])?.first)
            if (account == null) { call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "A valid merchant API key is required")); return@post }
            val keyId = runCatching { UUID.fromString(call.parameters["keyId"]) }.getOrNull()
            if (keyId == null) { call.respond(HttpStatusCode.BadRequest, ApiError("invalid_api_key_id", "A valid key ID is required")); return@post }
            if (!accountService.revokeKey(account.id, keyId)) { call.respond(HttpStatusCode.NotFound, ApiError("api_key_not_found", "Active API key not found")); return@post }
            call.respond(ApiMessage("revoked"))
        }

        post("/api/account/api-keys/{keyId}/rotate") {
            val account = authenticatedAccount(call.request.headers["Authorization"], humanAuth.session(call.request.cookies["gateway_session"])?.first)
            if (account == null) { call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "A valid merchant API key is required")); return@post }
            val keyId = runCatching { UUID.fromString(call.parameters["keyId"]) }.getOrNull()
            if (keyId == null) { call.respond(HttpStatusCode.BadRequest, ApiError("invalid_api_key_id", "A valid key ID is required")); return@post }
            val request = runCatching { call.receive<RotateApiKeyRequest>() }.getOrElse {
                call.respond(HttpStatusCode.BadRequest, ApiError("invalid_request", "Invalid API key rotation request")); return@post
            }
            val created = runCatching { accountService.rotateKey(account.id, keyId, request.name) }.getOrElse {
                call.respond(HttpStatusCode.NotFound, ApiError("api_key_not_found", it.message ?: "API key not found")); return@post
            }
            call.respond(CreatedApiKeyResponse(MerchantApiKeyResponse.from(created.key), created.secret))
        }

        post("/api/payments") {
            val request = runCatching { call.receive<InitiatePaymentRequest>() }.getOrElse {
                call.respond(HttpStatusCode.BadRequest, ApiError("invalid_request", "Invalid payment request")); return@post
            }
            val amount = request.amount.toBigDecimalOrNull()
            val account = authenticatedAccount(call.request.headers["Authorization"], humanAuth.session(call.request.cookies["gateway_session"])?.first)
            if (account == null) {
                call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "A valid merchant API key is required")); return@post
            }
            if (amount == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("invalid_payment", "A valid amount is required")); return@post
            }
            val command = InitiatePaymentCommand(account.id, request.email, amount, request.currency, request.idempotencyKey, request.phoneNumber, request.projectId?.let { runCatching { UUID.fromString(it) }.getOrNull() }, request.description)
            if (request.projectId != null && command.projectId == null) { call.respond(HttpStatusCode.BadRequest, ApiError("invalid_project_id", "A valid project ID is required")); return@post }
            paymentService.initiate(request.provider, command)
                .fold(
                    onSuccess = { p -> call.respond(HttpStatusCode.Created, PaymentResponse.from(p)) },
                    onFailure = { e -> call.respond(HttpStatusCode.BadRequest, ApiError("payment_initiation_failed", e.message ?: "Unable to initiate payment")) }
                )
        }

        get("/api/payments/{reference}/history") {
            val account = authenticatedAccount(call.request.headers["Authorization"], humanAuth.session(call.request.cookies["gateway_session"])?.first)
            if (account == null) { call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "A valid merchant API key is required")); return@get }
            val payment = store.findByGatewayReference(call.parameters["reference"].orEmpty())?.takeIf { it.accountId == account.id }
            if (payment == null) { call.respond(HttpStatusCode.NotFound, ApiError("payment_not_found", "Payment not found")); return@get }
            call.respond(store.paymentStatusHistory(payment.id).map(PaymentStatusHistoryResponse::from))
        }

        get("/api/payments/{reference}") {
            val account = authenticatedAccount(call.request.headers["Authorization"], humanAuth.session(call.request.cookies["gateway_session"])?.first)
            if (account == null) { call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "A valid merchant API key is required")); return@get }
            val reference = call.parameters["reference"].orEmpty()
            val payment = store.findByGatewayReference(reference)?.takeIf { it.accountId == account.id }
            if (payment == null) call.respond(HttpStatusCode.NotFound, ApiError("payment_not_found", "Payment not found"))
            else call.respond(PaymentResponse.from(payment))
        }

        get("/api/payments") {
            val account = authenticatedAccount(call.request.headers["Authorization"], humanAuth.session(call.request.cookies["gateway_session"])?.first)
            if (account == null) { call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "A valid merchant API key is required")); return@get }
            val status = call.request.queryParameters["status"]?.let { runCatching { PaymentStatus.valueOf(it.uppercase()) }.getOrNull() }
            if (call.request.queryParameters["status"] != null && status == null) { call.respond(HttpStatusCode.BadRequest, ApiError("invalid_status", "Unknown payment status")); return@get }
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
            val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
            val projectId = call.request.queryParameters["projectId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (call.request.queryParameters["projectId"] != null && projectId == null) { call.respond(HttpStatusCode.BadRequest, ApiError("invalid_project_id", "A valid project ID is required")); return@get }
            val from = call.request.queryParameters["from"]?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }
            val to = call.request.queryParameters["to"]?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }
            if ((call.request.queryParameters["from"] != null && from == null) || (call.request.queryParameters["to"] != null && to == null) || (from != null && to != null && from > to)) { call.respond(HttpStatusCode.BadRequest, ApiError("invalid_date_range", "Use valid ISO-8601 from/to timestamps")); return@get }
            call.respond(store.listPayments(account.id, status, call.request.queryParameters["provider"], call.request.queryParameters["currency"], projectId, from, to, limit, offset).map(PaymentResponse::from))
        }

        get("/api/payments/summary") {
            val account = authenticatedAccount(call.request.headers["Authorization"], humanAuth.session(call.request.cookies["gateway_session"])?.first)
            if (account == null) { call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "A valid merchant API key is required")); return@get }
            val total = store.paymentTotals(account.id)
            call.respond(PaymentSummaryResponse(total.count, total.succeededCount, total.pendingCount, total.failedCount, total.reversedCount,
                total.amountsByCurrency.mapValues { it.value.toPlainString() }, total.succeededByCurrency.mapValues { it.value.toPlainString() }))
        }

        get("/api/customers") {
            val account = authenticatedAccount(call.request.headers["Authorization"], humanAuth.session(call.request.cookies["gateway_session"])?.first)
            if (account == null) { call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "A valid merchant API key is required")); return@get }
            call.respond(store.listCustomers(account.id).map(CustomerResponse::from))
        }
        get("/api/customers/{customerId}") {
            val account = authenticatedAccount(call.request.headers["Authorization"], humanAuth.session(call.request.cookies["gateway_session"])?.first)
            if (account == null) { call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "A valid merchant API key is required")); return@get }
            val customerId = runCatching { UUID.fromString(call.parameters["customerId"]) }.getOrNull()
            if (customerId == null) { call.respond(HttpStatusCode.BadRequest, ApiError("invalid_customer_id", "A valid customer ID is required")); return@get }
            val customer = store.findCustomer(account.id, customerId)
            if (customer == null) { call.respond(HttpStatusCode.NotFound, ApiError("customer_not_found", "Customer not found")); return@get }
            call.respond(CustomerDetailResponse(CustomerResponse.from(customer), store.customerPayments(account.id, customerId).map(PaymentResponse::from), store.projectsForCustomer(account.id, customerId).map(ProjectResponse::from)))
        }
        patch("/api/customers/{customerId}") {
            val account = authenticatedAccount(call.request.headers["Authorization"], humanAuth.session(call.request.cookies["gateway_session"])?.first)
            if (account == null) { call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "A valid merchant API key is required")); return@patch }
            val customerId = runCatching { UUID.fromString(call.parameters["customerId"]) }.getOrNull()
            val req = runCatching { call.receive<UpdateCustomerRequest>() }.getOrNull()
            if (customerId == null || req == null || req.displayName?.length ?: 0 > 200) { call.respond(HttpStatusCode.BadRequest, ApiError("invalid_customer", "A valid customer name is required")); return@patch }
            val customer = store.updateCustomer(account.id, customerId, req.displayName)
            if (customer == null) call.respond(HttpStatusCode.NotFound, ApiError("customer_not_found", "Customer not found"))
            else call.respond(CustomerResponse.from(customer))
        }

        post("/api/payments/{reference}/reconcile") {
            val account = authenticatedAccount(call.request.headers["Authorization"], humanAuth.session(call.request.cookies["gateway_session"])?.first)
            if (account == null) { call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "A valid merchant API key is required")); return@post }
            val payment = store.findByGatewayReference(call.parameters["reference"].orEmpty())?.takeIf { it.accountId == account.id }
            if (payment == null) { call.respond(HttpStatusCode.NotFound, ApiError("payment_not_found", "Payment not found")); return@post }
            if (payment.status != PaymentStatus.PENDING) { call.respond(ReconcileResponse("unchanged", PaymentResponse.from(payment))); return@post }
            val wasReconciled = reconciler.reconcile(payment)
            val latest = store.findByProviderReference(payment.provider, payment.providerReference) ?: payment
            val status = when { wasReconciled -> "reconciled"; latest.status != PaymentStatus.PENDING -> "unchanged"; latest.lastProviderError != null -> "provider_unavailable"; else -> "still_pending" }
            call.respond(ReconcileResponse(status, PaymentResponse.from(latest)))
        }

        get("/api/ops/payment-events") {
            if (!authorizedOperations(call.request.headers["Authorization"], humanAuth.session(call.request.cookies["gateway_session"])?.first)) { call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Operations authorization required")); return@get }
            val status = call.request.queryParameters["status"]
            if (status != null && status !in setOf("received", "processed", "failed")) { call.respond(HttpStatusCode.BadRequest, ApiError("invalid_status", "Unknown event status")); return@get }
            val events = store.listEvents(status, call.request.queryParameters["limit"]?.toIntOrNull() ?: 50, call.request.queryParameters["offset"]?.toIntOrNull() ?: 0)
            call.respond(events.map(PaymentEventResponse::from))
        }

        post("/api/ops/payment-events/{eventId}/replay") {
            if (!authorizedOperations(call.request.headers["Authorization"], humanAuth.session(call.request.cookies["gateway_session"])?.first)) { call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Operations authorization required")); return@post }
            val id = runCatching { UUID.fromString(call.parameters["eventId"]) }.getOrNull()
            if (id == null) { call.respond(HttpStatusCode.BadRequest, ApiError("invalid_event_id", "A valid event ID is required")); return@post }
            if (!eventReplay.replay(id)) { store.audit("payment_event_replay_failed", id.toString()); call.respond(HttpStatusCode.Conflict, ApiError("event_not_replayable", "Failed event was not found or could not be replayed")); return@post }
            nginxQueue.enqueueAll()
            store.audit("payment_event_replayed", id.toString())
            call.respond(ApiMessage("replayed"))
        }

        get("/api/ops/payment-events/{eventId}") {
            if (!authorizedOperations(call.request.headers["Authorization"], humanAuth.session(call.request.cookies["gateway_session"])?.first)) { call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Operations authorization required")); return@get }
            val id = runCatching { UUID.fromString(call.parameters["eventId"]) }.getOrNull()
            if (id == null) { call.respond(HttpStatusCode.BadRequest, ApiError("invalid_event_id", "A valid event ID is required")); return@get }
            val event = store.getEvent(id)
            if (event == null) call.respond(HttpStatusCode.NotFound, ApiError("event_not_found", "Payment event not found"))
            else { store.audit("payment_event_inspected", id.toString()); call.respond(PaymentEventDetailResponse.from(event)) }
        }

        get("/api/sites") {
            val account = authenticatedAccount(call.request.headers["Authorization"], humanAuth.session(call.request.cookies["gateway_session"])?.first)
            if (account == null) { call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "A valid merchant API key is required")); return@get }
            call.respond(store.listSites(account.id).map(SiteResponse::from))
        }
        delete("/api/sites/{siteId}") {
            val account = authenticatedAccount(call.request.headers["Authorization"], humanAuth.session(call.request.cookies["gateway_session"])?.first)
            if (account == null) { call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "A valid merchant API key is required")); return@delete }
            val id = runCatching { UUID.fromString(call.parameters["siteId"]) }.getOrNull()
            if (id == null) { call.respond(HttpStatusCode.BadRequest, ApiError("invalid_site_id", "A valid site ID is required")); return@delete }
            if (store.listSites(account.id).none { it.id == id }) { call.respond(HttpStatusCode.NotFound, ApiError("site_not_found", "Site not found")); return@delete }
            val result = nginxQueue.removeSite(account.id, id)
            result.fold(
                onSuccess = {
                    store.audit("site_deleted", id.toString(), "Gateway-managed Nginx config removed")
                    call.respond(HttpStatusCode.NoContent)
                },
                onFailure = { error ->
                    call.respond(HttpStatusCode.Conflict, ApiError("site_delete_failed", error.message ?: "Unable to remove the managed site"))
                }
            )
        }
        post("/api/sites") {
            val account = authenticatedAccount(call.request.headers["Authorization"], humanAuth.session(call.request.cookies["gateway_session"])?.first)
            if (account == null) { call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "A valid merchant API key is required")); return@post }
            val req = runCatching { call.receive<CreateSiteRequest>() }.getOrNull()
            if (req == null) { call.respond(HttpStatusCode.BadRequest, ApiError("invalid_site", "A valid site request is required")); return@post }
            val hostname = runCatching { SiteInput.hostname(req.hostname.removePrefix("https://").removePrefix("http://").trimEnd('/')) }.getOrNull()
            val upstream = runCatching { SiteInput.localPort(req.port) }.getOrNull()
            val projectId = req.projectId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            val billingAmount = req.billingAmount.toBigDecimalOrNull()
            if (hostname == null || upstream == null || projectId == null || req.template != "proxy" || !validSiteBilling(billingAmount)) {
                call.respond(HttpStatusCode.BadRequest, ApiError("invalid_site", "Provide a valid hostname, application port, project, and non-negative KES amount with up to two decimals")); return@post
            }
            if (!GatewayConfig.nginxEnabled) { call.respond(HttpStatusCode.Conflict, ApiError("nginx_disabled", "Enable Nginx enforcement before connecting a site")); return@post }
            val site = runCatching { store.createConfiguredSite(account.id, hostname, upstream, hostname, req.template, projectId, billingAmount!!) }.getOrElse { call.respond(HttpStatusCode.Conflict, ApiError("site_creation_failed", it.message ?: "Unable to create site")); return@post }
            nginxQueue.enqueue(site.id)
            call.respond(HttpStatusCode.Created, SiteResponse.from(site))
        }
        put("/api/sites/{siteId}") {
            val account = authenticatedAccount(call.request.headers["Authorization"], humanAuth.session(call.request.cookies["gateway_session"])?.first)
            if (account == null) { call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "A valid merchant API key is required")); return@put }
            val id = runCatching { UUID.fromString(call.parameters["siteId"]) }.getOrNull()
            val req = runCatching {
                Json { ignoreUnknownKeys = true; isLenient = true }
                    .decodeFromString<UpdateSiteRequest>(call.receiveText())
            }.onFailure { error ->
                call.application.environment.log.warn(
                    "Could not decode site update request (content type: ${call.request.headers[io.ktor.http.HttpHeaders.ContentType]}): ${error.message}"
                )
            }.getOrNull()
            if (id == null) { call.respond(HttpStatusCode.BadRequest, ApiError("invalid_site_id", "A valid site ID is required")); return@put }
            if (req == null) { call.respond(HttpStatusCode.BadRequest, ApiError("invalid_site_request", "The site settings request could not be read. Refresh the page and try again.")); return@put }
            val hostname = runCatching { SiteInput.hostname(req.hostname) }.getOrNull()
            val upstream = req.upstreamUrl?.let { runCatching { SiteInput.upstream(it) }.getOrNull() }
            val tls = runCatching { SiteInput.tlsRef(req.tlsRef) }.getOrNull()
            val projectId = req.projectId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            val billingAmount = req.billingAmount?.toBigDecimalOrNull()
            if (hostname == null || req.upstreamUrl != null && upstream == null || req.tlsRef != null && tls == null || req.projectId != null && projectId == null || req.template != "proxy" || billingAmount != null && !validSiteBilling(billingAmount)) {
                call.respond(HttpStatusCode.BadRequest, ApiError("invalid_site", "Hostname, upstream URL, TLS reference, or template is invalid")); return@put
            }
            val site = runCatching { store.updateSite(account.id, id, hostname, upstream, tls, req.template, projectId, billingAmount) }.getOrElse {
                call.respond(HttpStatusCode.Conflict, ApiError("site_update_failed", it.message ?: "Unable to update site")); return@put
            }
            if (site == null) { call.respond(HttpStatusCode.NotFound, ApiError("site_not_found", "Site not found")); return@put }
            nginxQueue.enqueue(id)
            call.respond(SiteResponse.from(site))
        }
        patch("/api/sites/{siteId}/block") {
            val account = authenticatedAccount(call.request.headers["Authorization"], humanAuth.session(call.request.cookies["gateway_session"])?.first)
            if (account == null) { call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "A valid merchant API key is required")); return@patch }
            val id = runCatching { UUID.fromString(call.parameters["siteId"]) }.getOrNull()
            val req = runCatching { call.receive<SetSiteBlockRequest>() }.getOrNull()
            val reason = req?.reason?.trim()?.takeIf(String::isNotEmpty)
            if (id == null || req == null || req.reason != null && (reason == null || reason.length > 256)) {
                call.respond(HttpStatusCode.BadRequest, ApiError("invalid_site_block", "A valid site ID and a reason of at most 256 characters are required when blocking")); return@patch
            }
            if (!store.setManualSiteBlock(account.id, id, reason)) { call.respond(HttpStatusCode.NotFound, ApiError("site_not_found", "Site not found")); return@patch }
            nginxQueue.enqueue(id)
            store.audit(if (reason == null) "site_unblocked" else "site_blocked", id.toString(), reason)
            call.respond(SiteResponse.from(store.findSite(id)!!))
        }
        get("/api/projects") {
            val account = authenticatedAccount(call.request.headers["Authorization"], humanAuth.session(call.request.cookies["gateway_session"])?.first)
            if (account == null) { call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "A valid merchant API key is required")); return@get }
            call.respond(store.listProjects(account.id).map(ProjectResponse::from))
        }
        get("/api/projects/{projectId}") {
            val account = authenticatedAccount(call.request.headers["Authorization"], humanAuth.session(call.request.cookies["gateway_session"])?.first)
            if (account == null) { call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "A valid merchant API key is required")); return@get }
            val projectId = runCatching { UUID.fromString(call.parameters["projectId"]) }.getOrNull()
            if (projectId == null) { call.respond(HttpStatusCode.BadRequest, ApiError("invalid_project_id", "A valid project ID is required")); return@get }
            val project = store.findProject(account.id, projectId)
            if (project == null) { call.respond(HttpStatusCode.NotFound, ApiError("project_not_found", "Project not found")); return@get }
            val totals = store.projectPaymentTotals(account.id, projectId)
            val summary = PaymentSummaryResponse(totals.count, totals.succeededCount, totals.pendingCount, totals.failedCount, totals.reversedCount, totals.amountsByCurrency.mapValues { it.value.toPlainString() }, totals.succeededByCurrency.mapValues { it.value.toPlainString() })
            call.respond(ProjectDetailResponse(ProjectResponse.from(project), store.listSites(account.id).filter { it.projectId == projectId }.map(SiteResponse::from), store.customersForProject(account.id, projectId).map(CustomerResponse::from), store.listPayments(account.id, projectId = projectId, limit = 100).map(PaymentResponse::from), summary))
        }
        patch("/api/projects/{projectId}") {
            val account = authenticatedAccount(call.request.headers["Authorization"], humanAuth.session(call.request.cookies["gateway_session"])?.first)
            if (account == null) { call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "A valid merchant API key is required")); return@patch }
            val projectId = runCatching { UUID.fromString(call.parameters["projectId"]) }.getOrNull()
            val req = runCatching { call.receive<UpdateProjectStatusRequest>() }.getOrNull()
            if (projectId == null || req == null || req.status !in setOf("active", "suspended", "archived") || req.reason?.length ?: 0 > 256 || req.status == "suspended" && req.reason.isNullOrBlank()) { call.respond(HttpStatusCode.BadRequest, ApiError("invalid_project_status", "Status must be active, suspended, or archived; a reason is required to suspend a project")); return@patch }
            val project = runCatching { store.setProjectStatus(account.id, projectId, req.status, req.reason) }.getOrElse {
                call.respond(HttpStatusCode.BadRequest, ApiError("project_update_failed", it.message ?: "Unable to update project")); return@patch
            }
            if (project == null) { call.respond(HttpStatusCode.NotFound, ApiError("project_not_found", "Project not found")); return@patch }
            nginxQueue.enqueueAll()
            store.audit("project_status_changed", projectId.toString(), "${req.status}:${req.reason.orEmpty()}")
            call.respond(ProjectResponse.from(project))
        }
        post("/api/projects") {
            val account = authenticatedAccount(call.request.headers["Authorization"], humanAuth.session(call.request.cookies["gateway_session"])?.first)
            if (account == null) { call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "A valid merchant API key is required")); return@post }
            val req = runCatching { call.receive<CreateProjectRequest>() }.getOrNull()
            if (req == null || req.name.isBlank() || req.name.length > 200 || req.billingReference?.length ?: 0 > 128) { call.respond(HttpStatusCode.BadRequest, ApiError("invalid_project", "A valid project name and optional billing reference are required")); return@post }
            val siteId = req.siteId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (req.siteId != null && siteId == null) { call.respond(HttpStatusCode.BadRequest, ApiError("invalid_site_id", "A valid site ID is required")); return@post }
            val project = runCatching { store.createGroupedProject(account.id, siteId, req.name.trim(), req.billingReference?.trim()?.takeIf(String::isNotBlank)) }.getOrElse { call.respond(HttpStatusCode.BadRequest, ApiError("project_creation_failed", it.message ?: "Unable to create project")); return@post }
            call.respond(HttpStatusCode.Created, ProjectResponse.from(project))
        }

        get("/api/ops/nginx/configs") {
            if (!authorizedOperations(call.request.headers["Authorization"], humanAuth.session(call.request.cookies["gateway_session"])?.first)) { call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Operations authorization required")); return@get }
            call.respond(NginxConfigInspectionResponse(GatewayConfig.nginxEnabled, nginxDriver.inspectExternalConfigs(), nginxQueue.orphanedFiles()))
        }
        post("/api/ops/nginx/reconcile") {
            if (!authorizedOperations(call.request.headers["Authorization"], humanAuth.session(call.request.cookies["gateway_session"])?.first)) { call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Operations authorization required")); return@post }
            val expired = nginxQueue.expireGrace()
            val queued = nginxQueue.reconcile()
            store.audit("nginx_reconcile_requested", null, "grace_expired=$expired, drifted=$queued")
            call.respond(NginxReconcileResponse(if (GatewayConfig.nginxEnabled) "queued" else "disabled", expired, queued, nginxQueue.orphanedFiles()))
        }
        post("/api/ops/nginx/sites/{siteId}/apply") {
            if (!authorizedOperations(call.request.headers["Authorization"], humanAuth.session(call.request.cookies["gateway_session"])?.first)) { call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Operations authorization required")); return@post }
            val id = runCatching { UUID.fromString(call.parameters["siteId"]) }.getOrNull()
            if (id == null) { call.respond(HttpStatusCode.BadRequest, ApiError("invalid_site_id", "A valid site ID is required")); return@post }
            if (store.findSite(id) == null) { call.respond(HttpStatusCode.NotFound, ApiError("site_not_found", "Site not found")); return@post }
            nginxQueue.enqueue(id)
            store.audit("nginx_site_apply_requested", id.toString())
            call.respond(mapOf("status" to if (GatewayConfig.nginxEnabled) "queued" else "disabled"))
        }
        put("/api/ops/sites/{siteId}/entitlement") {
            if (!authorizedOperations(call.request.headers["Authorization"], humanAuth.session(call.request.cookies["gateway_session"])?.first)) { call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Operations authorization required")); return@put }
            val id = runCatching { UUID.fromString(call.parameters["siteId"]) }.getOrNull()
            val req = runCatching { call.receive<SetEntitlementRequest>() }.getOrNull()
            val effectiveAt = req?.effectiveAt?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }
            if (id == null || req == null || req.state !in setOf("active", "grace", "suspended", "disabled_by_admin") || req.reason.isBlank() || req.reason.length > 128 || req.effectiveAt != null && effectiveAt == null || req.state == "grace" && (effectiveAt == null || effectiveAt <= java.time.Instant.now())) {
                call.respond(HttpStatusCode.BadRequest, ApiError("invalid_entitlement", "State, reason, and future effectiveAt for grace are required")); return@put
            }
            if (!store.setEntitlement(id, req.state, req.reason, effectiveAt)) { call.respond(HttpStatusCode.NotFound, ApiError("site_not_found", "Site not found")); return@put }
            nginxQueue.enqueue(id)
            store.audit("site_entitlement_changed", id.toString(), "${req.state}:${req.reason}")
            call.respond(SiteResponse.from(store.findSite(id)!!))
        }

        post("/api/payments/paystack/webhook") {
            val raw = runCatching { call.receiveText() }.getOrElse { call.respond(HttpStatusCode.BadRequest, ApiError("invalid_request", "Could not read request body")); return@post }
            val notificationProvider = providers.getValue("paystack")
            val headers = call.request.headers.entries().associate { it.key to it.value.joinToString(",") }
            if (notificationProvider.authenticateNotification(headers, raw).isFailure) {
                call.respond(HttpStatusCode.Unauthorized, ApiError("invalid_signature", "Paystack signature verification failed")); return@post
            }
            val decoded = notificationProvider.decodeNotification(raw).getOrElse {
                val unsupported: String? = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonObject["event"]?.jsonPrimitive?.content }.getOrNull()
                if (unsupported != null && unsupported !in setOf("charge.success", "charge.failed", "charge.reversed")) {
                    val record = com.gateway.payment.domain.NewPaymentEvent("paystack", unsupported, "$unsupported:ignored:${java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { b -> "%02x".format(b) }}", null, raw)
                    val result = eventProcessor.process(record, null)
                    call.respond(HttpStatusCode.OK, WebhookResponse(if (result == EventResult.DUPLICATE) "duplicate" else "ignored")); return@post
                }
                call.respond(HttpStatusCode.BadRequest, ApiError("invalid_event", "Paystack event could not be decoded")); return@post
            }
            val event = decoded.status?.let { com.gateway.payment.domain.ProviderPaymentEvent("paystack", decoded.reference.orEmpty(), it, decoded.amount, decoded.currency, decoded.occurredAt, decoded.providerTransactionId) }
            val record = com.gateway.payment.domain.NewPaymentEvent("paystack", decoded.eventType, decoded.deduplicationKey, decoded.reference, decoded.rawPayload)
            when (eventProcessor.process(record, event)) {
                EventResult.PROCESSED -> { nginxQueue.enqueueAll(); call.respond(HttpStatusCode.OK, WebhookResponse("processed")) }
                EventResult.DUPLICATE -> call.respond(HttpStatusCode.OK, WebhookResponse("duplicate"))
                EventResult.REJECTED -> call.respond(HttpStatusCode.OK, WebhookResponse("rejected"))
            }
        }

        post("/api/payments/mpesa/callback") {
            val raw = runCatching { call.receiveText() }.getOrElse { call.respond(HttpStatusCode.BadRequest, ApiError("invalid_request", "Could not read request body")); return@post }
            val headers = call.request.headers.entries().associate { it.key to it.value.joinToString(",") }.toMutableMap()
            headers["x-gateway-callback-token"] = call.request.queryParameters["token"] ?: headers["x-gateway-callback-token"].orEmpty()
            if (mpesaProvider.authenticateNotification(headers, raw, call.request.queryParameters["token"]).isFailure) { call.respond(HttpStatusCode.Unauthorized, ApiError("invalid_callback", "M-Pesa callback authentication failed")); return@post }
            val decoded = mpesaProvider.decodeNotification(raw).getOrElse { call.respond(HttpStatusCode.BadRequest, ApiError("invalid_callback", "M-Pesa callback could not be decoded")); return@post }
            val providerReference = decoded.reference.orEmpty()
            val payment = store.findByProviderRequestId("mpesa", providerReference)
            if (payment == null) { call.respond(HttpStatusCode.OK, WebhookResponse("unmatched")); return@post }
            val gatewayReference = payment.providerReference
            val event = decoded.status?.let { ProviderPaymentEvent("mpesa", gatewayReference, it, decoded.amount, decoded.currency, decoded.occurredAt, decoded.providerTransactionId) }
            val record = com.gateway.payment.domain.NewPaymentEvent("mpesa", decoded.eventType, decoded.deduplicationKey, gatewayReference, decoded.rawPayload)
            when (eventProcessor.process(record, event)) {
                EventResult.PROCESSED -> { nginxQueue.enqueueAll(); call.respond(HttpStatusCode.OK, WebhookResponse("accepted")) }
                EventResult.DUPLICATE -> call.respond(HttpStatusCode.OK, WebhookResponse("accepted"))
                EventResult.REJECTED -> call.respond(HttpStatusCode.OK, WebhookResponse("rejected"))
            }
        }

        get("/api/payments/paystack/callback") {
            val reference = call.request.queryParameters["reference"]
                ?: call.request.queryParameters["trxref"]
            if (reference.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, ApiError("invalid_callback", "Payment reference is required")); return@get
            }
            val payment = store.findByGatewayReference(reference)?.takeIf { it.provider == "paystack" }
            if (payment == null) {
                call.respond(HttpStatusCode.NotFound, ApiError("payment_not_found", "Payment not found")); return@get
            }
            val verified = provider.queryStatus(payment).getOrNull()
            if (verified?.status == PaymentStatus.SUCCEEDED) {
                val event = ProviderPaymentEvent("paystack", payment.gatewayReference, PaymentStatus.SUCCEEDED, verified.amount, verified.currency, verified.occurredAt, verified.providerTransactionId)
                val record = com.gateway.payment.domain.NewPaymentEvent("paystack", "callback.verify", "callback.verify:$reference", payment.gatewayReference, "verified-via-callback")
                val result = eventProcessor.process(record, event)
                if (result == EventResult.REJECTED) {
                    call.respond(HttpStatusCode.BadGateway, ApiError("payment_verification_failed", "Verified payment did not match the expected amount and currency")); return@get
                }
                if (result == EventResult.PROCESSED) nginxQueue.enqueueAll()
            }
            val refreshed = store.findByGatewayReference(reference) ?: payment
            call.respond(PaymentResponse.from(refreshed))
        }
    }
}

private fun authenticatedAccount(authorization: String?, user: GatewayUser? = null) =
    accountService.authenticate(authorization?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substringAfter(' '))
        ?: user?.takeIf { it.role in setOf("owner", "operator") }?.let { accountService.account(it.accountId) }

private fun authorizedOperations(authorization: String?, user: GatewayUser? = null): Boolean {
    if (user?.role in setOf("owner", "operator")) return true
    val expected = GatewayConfig.opsToken.takeIf(String::isNotBlank) ?: return false
    val supplied = authorization?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substringAfter(' ') ?: return false
    return java.security.MessageDigest.isEqual(expected.toByteArray(), supplied.toByteArray())
}

private fun redactPayload(provider: String, payload: String): String {
    if (provider !in setOf("paystack", "mpesa")) return "[payload withheld for unknown provider]"
    val sensitive = setOf("email", "authorization_code", "customer_code", "phone", "phonenumber", "phone_number", "msisdn")
    fun redact(value: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonElement = when (value) {
        is kotlinx.serialization.json.JsonObject -> kotlinx.serialization.json.JsonObject(value.mapValues { (key, child) -> if (key.lowercase() in sensitive) kotlinx.serialization.json.JsonPrimitive("[redacted]") else redact(child) })
        is kotlinx.serialization.json.JsonArray -> kotlinx.serialization.json.JsonArray(value.map(::redact))
        else -> value
    }
    return runCatching { kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), redact(kotlinx.serialization.json.Json.parseToJsonElement(payload))) }.getOrDefault("[payload withheld: invalid JSON]")
}

@Serializable data class InitiatePaymentRequest(val email: String = "", val amount: String, val currency: String, val idempotencyKey: String? = null, val provider: String = "paystack", val phoneNumber: String? = null, val projectId: String? = null, val description: String? = null)
@Serializable data class LoginRequest(val email: String, val password: String)
@Serializable data class AcceptInviteRequest(val token: String, val password: String)
@Serializable data class CreateUserRequest(val email: String, val displayName: String, val role: String)
@Serializable data class UserResponse(val id: String, val email: String, val displayName: String, val role: String, val accountId: String) {
    companion object { fun from(user: GatewayUser) = UserResponse(user.id.toString(), user.email, user.displayName, user.role, user.accountId.toString()) }
}
@Serializable data class LoginResponse(val user: UserResponse, val expiresAt: String)
@Serializable data class CreatedInviteResponse(val user: UserResponse, val inviteToken: String, val expiresAt: String)
@Serializable data class CreateAccountRequest(val name: String, val email: String? = null)
@Serializable data class CreatedAccountResponse(val accountId: String, val name: String, val email: String?, val apiKey: String)
@Serializable data class CreateApiKeyRequest(val name: String)
@Serializable data class RotateApiKeyRequest(val name: String)
@Serializable data class MerchantApiKeyResponse(val id: String, val name: String, val prefix: String, val createdAt: String, val lastUsedAt: String?, val revokedAt: String?) {
    companion object {
        fun from(key: com.gateway.payment.domain.MerchantApiKey) = MerchantApiKeyResponse(key.id.toString(), key.name, key.prefix, key.createdAt.toString(), key.lastUsedAt?.toString(), key.revokedAt?.toString())
    }
}
@Serializable data class CreatedApiKeyResponse(val key: MerchantApiKeyResponse, val secret: String)
@Serializable data class ApiMessage(val status: String)
@Serializable data class PaymentResponse(val id: String, val provider: String, val reference: String, val amount: String, val currency: String, val status: String, val checkoutUrl: String?, val projectId: String?, val customerId: String? = null, val customerEmail: String? = null, val customerPhone: String? = null, val description: String? = null, val createdAt: String? = null, val paidAt: String? = null) {
    companion object { fun from(p: com.gateway.payment.domain.Payment) = PaymentResponse(p.id.toString(), p.provider, p.gatewayReference, p.amount.toPlainString(), p.currency, p.status.name.lowercase(), p.checkoutUrl, p.projectId?.toString(), p.customerId?.toString(), p.requestEmail, p.customerPhone, p.description, p.createdAt.toString(), p.paidAt?.toString()) }
}
@Serializable data class ApiError(val code: String, val message: String)
@Serializable data class WebhookResponse(val status: String)
@Serializable data class PaymentSummaryResponse(val count: Long, val succeeded: Long, val pending: Long, val failed: Long, val reversed: Long, val amountsByCurrency: Map<String, String>, val succeededAmountsByCurrency: Map<String, String>)
@Serializable data class ReconcileResponse(val status: String, val payment: PaymentResponse)
@Serializable data class PaymentEventResponse(val id: String, val provider: String, val eventType: String, val reference: String?, val status: String, val receivedAt: String, val processedAt: String?, val processingError: String?) {
    companion object { fun from(event: com.gateway.payment.domain.PaymentEvent) = PaymentEventResponse(event.id.toString(), event.provider, event.eventType, event.providerReference, event.status, event.receivedAt.toString(), event.processedAt?.toString(), event.processingError) }
}
@Serializable data class PaymentEventDetailResponse(val id: String, val provider: String, val eventType: String, val reference: String?, val status: String, val receivedAt: String, val processedAt: String?, val processingError: String?, val attemptCount: Int, val rawPayload: String) {
    companion object { fun from(event: com.gateway.payment.domain.PaymentEvent) = PaymentEventDetailResponse(event.id.toString(), event.provider, event.eventType, event.providerReference, event.status, event.receivedAt.toString(), event.processedAt?.toString(), event.processingError, event.attemptCount, redactPayload(event.provider, event.rawPayload)) }
}
@Serializable data class PaymentStatusHistoryResponse(val previousStatus: String?, val status: String, val source: String, val providerTransactionId: String?, val occurredAt: String) {
    companion object { fun from(item: com.gateway.payment.domain.PaymentStatusChange) = PaymentStatusHistoryResponse(item.previousStatus?.name?.lowercase(), item.status.name.lowercase(), item.source, item.providerTransactionId, item.occurredAt.toString()) }
}
@Serializable data class CreateSiteRequest(val hostname: String, val port: Int, val projectId: String, val template: String = "proxy", val billingAmount: String = "0")
@Serializable data class UpdateSiteRequest(val hostname: String, val upstreamUrl: String? = null, val tlsRef: String? = null, val template: String = "proxy", val projectId: String? = null, val billingAmount: String? = null)
@Serializable data class SetSiteBlockRequest(val reason: String? = null)
@Serializable data class SetEntitlementRequest(val state: String, val reason: String, val effectiveAt: String? = null)
@Serializable data class NginxConfigInspectionResponse(val enabled: Boolean, val externalConfigs: List<com.gateway.enforcement.adapter.ExternalNginxConfig>, val gatewayOrphanedFiles: List<String>)
@Serializable data class NginxReconcileResponse(val status: String, val graceExpired: Int, val sitesQueued: Int, val gatewayOrphanedFiles: List<String>)
@Serializable data class SiteResponse(
    val id: String, val hostname: String, val createdAt: String, val projectId: String?, val upstreamUrl: String?, val tlsRef: String?, val template: String,
    val entitlementState: String, val stateReason: String, val stateChangedAt: String?, val stateEffectiveAt: String?,
    val appliedHash: String?, val applyStatus: String, val lastApplyError: String?,
    val billingAmount: String = "0.00", val manualBlockReason: String? = null
) {
    companion object { fun from(site: PaymentSite) = SiteResponse(
        site.id.toString(), site.hostname, site.createdAt.toString(), site.projectId?.toString(), site.upstreamUrl, site.tlsRef, site.template,
        site.effectiveEntitlementState, site.effectiveStateReason, site.stateChangedAt?.toString(), site.stateEffectiveAt?.toString(),
        site.appliedHash, site.applyStatus, site.lastApplyError, site.billingAmount.toPlainString(), site.manualBlockReason
    ) }
}
@Serializable data class CreateProjectRequest(val name: String, val siteId: String? = null, val billingReference: String? = null)
@Serializable data class ProjectResponse(val id: String, val siteId: String?, val name: String, val billingReference: String?, val createdAt: String, val status: String = "active", val statusReason: String? = null) {
    companion object { fun from(project: PaymentProject) = ProjectResponse(project.id.toString(), project.siteId?.toString(), project.name, project.billingReference, project.createdAt.toString(), project.status, project.statusReason) }
}
@Serializable data class ProjectDetailResponse(val project: ProjectResponse, val sites: List<SiteResponse>, val customers: List<CustomerResponse>, val recentPayments: List<PaymentResponse>, val paymentSummary: PaymentSummaryResponse)
@Serializable data class CustomerResponse(val id: String, val displayName: String?, val email: String?, val phoneNumber: String?, val createdAt: String, val updatedAt: String) {
    companion object { fun from(customer: com.gateway.payment.domain.PaymentCustomer) = CustomerResponse(customer.id.toString(), customer.displayName, customer.email, customer.phoneNumber, customer.createdAt.toString(), customer.updatedAt.toString()) }
}
@Serializable data class CustomerDetailResponse(val customer: CustomerResponse, val payments: List<PaymentResponse>, val projects: List<ProjectResponse>)
@Serializable data class UpdateCustomerRequest(val displayName: String?)
@Serializable data class UpdateProjectStatusRequest(val status: String, val reason: String? = null)

private fun validSiteBilling(amount: java.math.BigDecimal?): Boolean =
    amount != null && amount >= java.math.BigDecimal.ZERO && amount <= java.math.BigDecimal("999999999999.99") &&
        amount.scale().coerceAtLeast(0) <= 2
