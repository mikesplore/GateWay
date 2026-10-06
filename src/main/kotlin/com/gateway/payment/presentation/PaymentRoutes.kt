package com.gateway.payment.presentation

import com.gateway.config.GatewayConfig
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
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receiveText
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.util.UUID

private val store = ExposedPaymentStore()
private val provider = PaystackProvider()
private val mpesaProvider = MpesaProvider()
private val providers: Map<String, PaymentProvider> = listOf(provider, mpesaProvider).associateBy { it.name }
private val paymentService = ProviderPaymentService(store, providers)
private val eventProcessor = PaymentEventProcessor(store)
private val accountService = AccountService(store)
private val reconciler = PaymentReconciler(store, providers)
private val eventReplay = ProviderEventReplayService(store, providers)

fun Application.configurePaymentRoutes() {
    val jobs = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    if (System.getenv("RECONCILIATION_ENABLED")?.equals("false", true) != true) jobs.launch {
        while (isActive) {
            runCatching { reconciler.reconcile(java.time.Instant.now(), GatewayConfig.reconciliationBatchSize) }
            delay(com.gateway.config.GatewayConfig.reconciliationIntervalSeconds * 1000)
        }
    }
    monitor.subscribe(ApplicationStopped) { jobs.cancel() }
    routing {
        get("/api/health") { call.respond(mapOf("status" to "ok")) }
        get("/api/ready") {
            if (runCatching { DatabaseFactory.ready() }.getOrDefault(false)) call.respond(mapOf("status" to "ready"))
            else call.respond(HttpStatusCode.ServiceUnavailable, mapOf("status" to "not_ready"))
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
            val account = authenticatedAccount(call.request.headers["Authorization"])
            if (account == null) { call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "A valid merchant API key is required")); return@get }
            call.respond(accountService.listKeys(account.id).map(MerchantApiKeyResponse::from))
        }

        post("/api/account/api-keys") {
            val account = authenticatedAccount(call.request.headers["Authorization"])
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
            val account = authenticatedAccount(call.request.headers["Authorization"])
            if (account == null) { call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "A valid merchant API key is required")); return@post }
            val keyId = runCatching { UUID.fromString(call.parameters["keyId"]) }.getOrNull()
            if (keyId == null) { call.respond(HttpStatusCode.BadRequest, ApiError("invalid_api_key_id", "A valid key ID is required")); return@post }
            if (!accountService.revokeKey(account.id, keyId)) { call.respond(HttpStatusCode.NotFound, ApiError("api_key_not_found", "Active API key not found")); return@post }
            call.respond(ApiMessage("revoked"))
        }

        post("/api/account/api-keys/{keyId}/rotate") {
            val account = authenticatedAccount(call.request.headers["Authorization"])
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
            val account = authenticatedAccount(call.request.headers["Authorization"])
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
            val account = authenticatedAccount(call.request.headers["Authorization"])
            if (account == null) { call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "A valid merchant API key is required")); return@get }
            val payment = store.findByGatewayReference(call.parameters["reference"].orEmpty())?.takeIf { it.accountId == account.id }
            if (payment == null) { call.respond(HttpStatusCode.NotFound, ApiError("payment_not_found", "Payment not found")); return@get }
            call.respond(store.paymentStatusHistory(payment.id).map(PaymentStatusHistoryResponse::from))
        }

        get("/api/payments/{reference}") {
            val account = authenticatedAccount(call.request.headers["Authorization"])
            if (account == null) { call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "A valid merchant API key is required")); return@get }
            val reference = call.parameters["reference"].orEmpty()
            val payment = store.findByGatewayReference(reference)?.takeIf { it.accountId == account.id }
            if (payment == null) call.respond(HttpStatusCode.NotFound, ApiError("payment_not_found", "Payment not found"))
            else call.respond(PaymentResponse.from(payment))
        }

        get("/api/payments") {
            val account = authenticatedAccount(call.request.headers["Authorization"])
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
            val account = authenticatedAccount(call.request.headers["Authorization"])
            if (account == null) { call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "A valid merchant API key is required")); return@get }
            val total = store.paymentTotals(account.id)
            call.respond(PaymentSummaryResponse(total.count, total.succeededCount, total.pendingCount, total.failedCount, total.reversedCount,
                total.amountsByCurrency.mapValues { it.value.toPlainString() }, total.succeededByCurrency.mapValues { it.value.toPlainString() }))
        }

        post("/api/payments/{reference}/reconcile") {
            val account = authenticatedAccount(call.request.headers["Authorization"])
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
            if (!authorizedOperations(call.request.headers["Authorization"])) { call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Operations authorization required")); return@get }
            val status = call.request.queryParameters["status"]
            if (status != null && status !in setOf("received", "processed", "failed")) { call.respond(HttpStatusCode.BadRequest, ApiError("invalid_status", "Unknown event status")); return@get }
            val events = store.listEvents(status, call.request.queryParameters["limit"]?.toIntOrNull() ?: 50, call.request.queryParameters["offset"]?.toIntOrNull() ?: 0)
            call.respond(events.map(PaymentEventResponse::from))
        }

        post("/api/ops/payment-events/{eventId}/replay") {
            if (!authorizedOperations(call.request.headers["Authorization"])) { call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Operations authorization required")); return@post }
            val id = runCatching { UUID.fromString(call.parameters["eventId"]) }.getOrNull()
            if (id == null) { call.respond(HttpStatusCode.BadRequest, ApiError("invalid_event_id", "A valid event ID is required")); return@post }
            if (!eventReplay.replay(id)) { store.audit("payment_event_replay_failed", id.toString()); call.respond(HttpStatusCode.Conflict, ApiError("event_not_replayable", "Failed event was not found or could not be replayed")); return@post }
            store.audit("payment_event_replayed", id.toString())
            call.respond(ApiMessage("replayed"))
        }

        get("/api/ops/payment-events/{eventId}") {
            if (!authorizedOperations(call.request.headers["Authorization"])) { call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Operations authorization required")); return@get }
            val id = runCatching { UUID.fromString(call.parameters["eventId"]) }.getOrNull()
            if (id == null) { call.respond(HttpStatusCode.BadRequest, ApiError("invalid_event_id", "A valid event ID is required")); return@get }
            val event = store.getEvent(id)
            if (event == null) call.respond(HttpStatusCode.NotFound, ApiError("event_not_found", "Payment event not found"))
            else { store.audit("payment_event_inspected", id.toString()); call.respond(PaymentEventDetailResponse.from(event)) }
        }

        get("/api/sites") {
            val account = authenticatedAccount(call.request.headers["Authorization"])
            if (account == null) { call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "A valid merchant API key is required")); return@get }
            call.respond(store.listSites(account.id).map(SiteResponse::from))
        }
        post("/api/sites") {
            val account = authenticatedAccount(call.request.headers["Authorization"])
            if (account == null) { call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "A valid merchant API key is required")); return@post }
            val req = runCatching { call.receive<CreateSiteRequest>() }.getOrNull()
            val hostname = req?.hostname?.trim()?.lowercase()?.removePrefix("https://")?.removePrefix("http://")?.trimEnd('/')
            if (hostname.isNullOrBlank() || hostname.length > 253 || hostname.contains('/') || !hostname.contains('.')) { call.respond(HttpStatusCode.BadRequest, ApiError("invalid_site", "A valid site hostname is required")); return@post }
            val site = runCatching { store.createSite(account.id, hostname) }.getOrElse { call.respond(HttpStatusCode.Conflict, ApiError("site_creation_failed", it.message ?: "Unable to create site")); return@post }
            call.respond(HttpStatusCode.Created, SiteResponse.from(site))
        }
        get("/api/projects") {
            val account = authenticatedAccount(call.request.headers["Authorization"])
            if (account == null) { call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "A valid merchant API key is required")); return@get }
            call.respond(store.listProjects(account.id).map(ProjectResponse::from))
        }
        post("/api/projects") {
            val account = authenticatedAccount(call.request.headers["Authorization"])
            if (account == null) { call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "A valid merchant API key is required")); return@post }
            val req = runCatching { call.receive<CreateProjectRequest>() }.getOrNull()
            if (req == null || req.name.isBlank() || req.name.length > 200 || req.billingReference?.length ?: 0 > 128) { call.respond(HttpStatusCode.BadRequest, ApiError("invalid_project", "A valid project name, site ID, and optional billing reference are required")); return@post }
            val siteId = runCatching { UUID.fromString(req.siteId) }.getOrNull()
            if (siteId == null) { call.respond(HttpStatusCode.BadRequest, ApiError("invalid_site_id", "A valid site ID is required")); return@post }
            val project = runCatching { store.createProject(account.id, siteId, req.name.trim(), req.billingReference?.trim()?.takeIf(String::isNotBlank)) }.getOrElse { call.respond(HttpStatusCode.BadRequest, ApiError("project_creation_failed", it.message ?: "Unable to create project")); return@post }
            call.respond(HttpStatusCode.Created, ProjectResponse.from(project))
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
                EventResult.PROCESSED -> call.respond(HttpStatusCode.OK, WebhookResponse("processed"))
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
                EventResult.PROCESSED, EventResult.DUPLICATE -> call.respond(HttpStatusCode.OK, WebhookResponse("accepted"))
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
            }
            val refreshed = store.findByGatewayReference(reference) ?: payment
            call.respond(PaymentResponse.from(refreshed))
        }
    }
}

private fun authenticatedAccount(authorization: String?) =
    accountService.authenticate(authorization?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substringAfter(' '))

private fun authorizedOperations(authorization: String?): Boolean {
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
@Serializable data class PaymentResponse(val id: String, val provider: String, val reference: String, val amount: String, val currency: String, val status: String, val checkoutUrl: String?, val projectId: String?) {
    companion object { fun from(p: com.gateway.payment.domain.Payment) = PaymentResponse(p.id.toString(), p.provider, p.gatewayReference, p.amount.toPlainString(), p.currency, p.status.name.lowercase(), p.checkoutUrl, p.projectId?.toString()) }
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
@Serializable data class CreateSiteRequest(val hostname: String)
@Serializable data class SiteResponse(val id: String, val hostname: String, val createdAt: String) {
    companion object { fun from(site: PaymentSite) = SiteResponse(site.id.toString(), site.hostname, site.createdAt.toString()) }
}
@Serializable data class CreateProjectRequest(val siteId: String, val name: String, val billingReference: String? = null)
@Serializable data class ProjectResponse(val id: String, val siteId: String, val name: String, val billingReference: String?, val createdAt: String) {
    companion object { fun from(project: PaymentProject) = ProjectResponse(project.id.toString(), project.siteId.toString(), project.name, project.billingReference, project.createdAt.toString()) }
}
