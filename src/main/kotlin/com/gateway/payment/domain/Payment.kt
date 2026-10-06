package com.gateway.payment.domain

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

enum class PaymentStatus { INITIALIZING, PENDING, SUCCEEDED, FAILED, REVERSED, EXPIRED }

data class PaymentAccount(val id: UUID, val name: String, val email: String?, val createdAt: Instant)
data class MerchantApiKey(val id: UUID, val accountId: UUID, val name: String, val prefix: String, val createdAt: Instant, val lastUsedAt: Instant?, val revokedAt: Instant?)
data class CreatedApiKey(val key: MerchantApiKey, val secret: String)

data class Payment(
    val id: UUID,
    val accountId: UUID,
    val provider: String,
    val providerReference: String,
    val amount: BigDecimal,
    val currency: String,
    var status: PaymentStatus,
    val checkoutUrl: String? = null,
    val createdAt: Instant,
    val paidAt: Instant? = null,
    val projectId: UUID? = null,
    val idempotencyKey: String? = null,
    val providerTransactionId: String? = null,
    val providerRequestId: String? = null,
    val customerPhone: String? = null,
    val nextReconciliationAt: Instant? = null,
    val reconciliationAttempts: Int = 0,
    val lastProviderError: String? = null,
    val gatewayReference: String = providerReference,
    val requestEmail: String? = null,
    val description: String? = null
)

data class PaymentEvent(
    val id: UUID,
    val provider: String,
    val eventType: String,
    val deduplicationKey: String,
    val providerReference: String?,
    val normalizedStatus: PaymentStatus? = null,
    val normalizedAmount: BigDecimal? = null,
    val normalizedCurrency: String? = null,
    val providerTransactionId: String? = null,
    val rawPayload: String,
    val status: String,
    val receivedAt: Instant,
    val processedAt: Instant? = null,
    val processingError: String? = null,
    val attemptCount: Int = 0
)

data class NormalizedProviderNotification(
    val eventType: String,
    val deduplicationKey: String,
    val reference: String?,
    val status: PaymentStatus?,
    val amount: BigDecimal? = null,
    val currency: String? = null,
    val providerTransactionId: String? = null,
    val occurredAt: Instant? = null,
    val rawPayload: String
)

data class InitiatePaymentCommand(
    val accountId: UUID,
    val email: String,
    val amount: BigDecimal,
    val currency: String,
    val idempotencyKey: String? = null,
    val phoneNumber: String? = null,
    val projectId: UUID? = null,
    val description: String? = null,
    val gatewayReference: String? = null
)

data class InitiatedPayment(
    val reference: String,
    val checkoutUrl: String? = null,
    val providerRequestId: String? = null,
    val providerTransactionId: String? = null,
    val metadata: Map<String, String> = emptyMap()
)

data class ProviderPaymentStatus(
    val status: PaymentStatus?,
    val amount: BigDecimal? = null,
    val currency: String? = null,
    val providerTransactionId: String? = null,
    val occurredAt: Instant? = null,
    val pending: Boolean = false
)

enum class PaymentCapability { BROWSER_CHECKOUT, CUSTOMER_PROMPT, STATUS_QUERY, SIGNED_WEBHOOK, TOKEN_AUTHENTICATED_CALLBACK }

interface PaymentProvider {
    val name: String
    val capabilities: Set<PaymentCapability>
    suspend fun initiate(command: InitiatePaymentCommand): Result<InitiatedPayment>
    suspend fun authenticateNotification(headers: Map<String, String>, rawBody: String, requestToken: String? = null): Result<Unit>
    fun decodeNotification(rawBody: String): Result<NormalizedProviderNotification>
    suspend fun queryStatus(payment: Payment): Result<ProviderPaymentStatus>
}

data class PaymentProject(val id: UUID, val accountId: UUID, val siteId: UUID?, val name: String, val billingReference: String?, val createdAt: Instant)
data class PaymentSite(
    val id: UUID,
    val accountId: UUID,
    val hostname: String,
    val createdAt: Instant,
    val projectId: UUID? = null,
    val upstreamUrl: String? = null,
    val tlsRef: String? = null,
    val template: String = "proxy",
    val entitlementState: String = "suspended",
    val stateReason: String = "awaiting_payment",
    val stateChangedAt: Instant? = null,
    val stateEffectiveAt: Instant? = null,
    val appliedHash: String? = null,
    val applyStatus: String = "not_configured",
    val lastApplyError: String? = null
)
