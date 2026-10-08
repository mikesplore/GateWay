package com.gateway.payment.domain

import java.time.Instant

class ProviderPaymentService(private val payments: PaymentStore, private val providers: Map<String, PaymentProvider>) {
    suspend fun initiate(providerName: String, command: InitiatePaymentCommand): Result<Payment> {
        val provider = providers[providerName.lowercase()] ?: return Result.failure(IllegalArgumentException("Unsupported payment provider"))
        val validationError = when {
            command.amount <= java.math.BigDecimal.ZERO -> "Amount must be greater than zero"
            command.amount.scale().coerceAtLeast(0) > 2 -> "Amount cannot have more than two decimal places"
            !command.currency.matches(Regex("[A-Za-z]{3}")) -> "Currency must be a three-letter code"
            command.idempotencyKey != null && (command.idempotencyKey.isBlank() || command.idempotencyKey.length > 120) -> "Idempotency key must be 1 to 120 characters"
            command.email.length > 320 -> "Customer email is too long"
            command.phoneNumber != null && command.phoneNumber.length > 24 -> "Customer phone number is too long"
            PaymentCapability.BROWSER_CHECKOUT in provider.capabilities && command.email.isBlank() -> "A customer email is required"
            PaymentCapability.CUSTOMER_PROMPT in provider.capabilities && command.phoneNumber.isNullOrBlank() -> "A customer phone number is required for this payment channel"
            PaymentCapability.CUSTOMER_PROMPT in provider.capabilities && !command.currency.equals("KES", true) -> "This payment channel supports KES only"
            PaymentCapability.CUSTOMER_PROMPT in provider.capabilities && command.amount.stripTrailingZeros().scale() > 0 -> "This payment channel only supports whole KES amounts"
            command.gatewayReference != null && (command.gatewayReference.isBlank() || command.gatewayReference.length > 128) -> "Gateway reference must be 1 to 128 characters"
            else -> null
        }
        if (validationError != null) return Result.failure(IllegalArgumentException(validationError))
        if (!payments.accountExists(command.accountId)) return Result.failure(UnknownPaymentAccount())
        if (command.projectId != null && !payments.projectBelongsToAccount(command.projectId, command.accountId)) return Result.failure(IllegalArgumentException("Project not found for account"))
        if (command.projectId != null && !payments.projectAcceptsPayments(command.projectId)) return Result.failure(IllegalArgumentException("Archived projects cannot receive payments"))
        val reference = command.gatewayReference ?: "gw_${java.util.UUID.randomUUID().toString().replace("-", "")}" 
        val idempotencyKey = "${provider.name}:${command.idempotencyKey ?: reference}"
        val reservation = payments.reservePayment(command.accountId, command.amount.setScale(2), command.currency.uppercase(), idempotencyKey, command.projectId, command.email.takeIf(String::isNotBlank), command.phoneNumber, reference, command.description?.take(256))
        reservation.existing?.let { existing ->
            if (existing.amount.compareTo(command.amount) != 0 || !existing.currency.equals(command.currency, true) || existing.projectId != command.projectId || existing.customerPhone != command.phoneNumber || existing.requestEmail != command.email.takeIf(String::isNotBlank) || existing.description != command.description?.take(256)) return Result.failure(IllegalArgumentException("Idempotency key was already used with different payment details"))
            if (existing.status == PaymentStatus.INITIALIZING || existing.providerReference.startsWith("reservation:")) return Result.failure(IllegalStateException("Payment initiation outcome is still being recovered"))
            return Result.success(existing)
        }
        val normalized = command.copy(currency = command.currency.uppercase(), gatewayReference = reference)
        val initiated = provider.initiate(normalized).getOrElse { return Result.failure(it) }
        return runCatching {
            payments.completeReservation(reservation, provider.name, initiated.reference, initiated.checkoutUrl, initiated.providerRequestId ?: initiated.providerTransactionId, reference)
        }
    }
}

interface PaymentStore : AccountStore {
    fun findByProviderReference(provider: String, reference: String): Payment?
    fun findByGatewayReference(reference: String): Payment?
    fun findByProviderRequestId(provider: String, requestId: String): Payment?
    fun reservePayment(accountId: java.util.UUID, amount: java.math.BigDecimal, currency: String, idempotencyKey: String, projectId: java.util.UUID? = null, requestEmail: String? = null, phoneNumber: String? = null, gatewayReference: String? = null, description: String? = null): PaymentReservation
    fun completeReservation(reservation: PaymentReservation, provider: String, reference: String, checkoutUrl: String?, providerRequestId: String? = null, gatewayReference: String = reference): Payment
    fun listPayments(accountId: java.util.UUID, status: PaymentStatus? = null, provider: String? = null, currency: String? = null, projectId: java.util.UUID? = null, from: java.time.Instant? = null, to: java.time.Instant? = null, limit: Int = 50, offset: Int = 0): List<Payment>
    fun paymentTotals(accountId: java.util.UUID): PaymentTotals
    fun paymentStatusHistory(paymentId: java.util.UUID): List<PaymentStatusChange>
    fun claimStalePayments(before: java.time.Instant, limit: Int = 100): List<Payment>
    fun listEvents(status: String? = null, limit: Int = 50, offset: Int = 0): List<PaymentEvent>
    fun processProviderNotification(record: NewPaymentEvent, event: ProviderPaymentEvent?): String
    fun claimFailedEvent(eventId: java.util.UUID): PaymentEvent?
    fun finishReplayedEvent(eventId: java.util.UUID, event: ProviderPaymentEvent?, error: String?): Boolean
    fun listFailedEvents(limit: Int = 50): List<PaymentEvent>
    fun createSite(accountId: java.util.UUID, hostname: String): PaymentSite
    fun createConfiguredSite(accountId: java.util.UUID, hostname: String, upstreamUrl: String?, tlsRef: String?, template: String, projectId: java.util.UUID?, billingAmount: java.math.BigDecimal = java.math.BigDecimal.ZERO): PaymentSite = createSite(accountId, hostname)
    fun listSites(accountId: java.util.UUID): List<PaymentSite>
    fun deleteSite(accountId: java.util.UUID, siteId: java.util.UUID): Boolean = false
    fun updateSite(accountId: java.util.UUID, siteId: java.util.UUID, hostname: String, upstreamUrl: String?, tlsRef: String?, template: String, projectId: java.util.UUID?, billingAmount: java.math.BigDecimal? = null): PaymentSite? = null
    fun setEntitlement(siteId: java.util.UUID, state: String, reason: String, effectiveAt: java.time.Instant?): Boolean = false
    fun setManualSiteBlock(accountId: java.util.UUID, siteId: java.util.UUID, reason: String?): Boolean = false
    fun createProject(accountId: java.util.UUID, siteId: java.util.UUID, name: String, billingReference: String?): PaymentProject
    fun createGroupedProject(accountId: java.util.UUID, siteId: java.util.UUID?, name: String, billingReference: String?): PaymentProject =
        createProject(accountId, siteId ?: throw IllegalArgumentException("A site ID is required"), name, billingReference)
    fun listProjects(accountId: java.util.UUID): List<PaymentProject>
    fun projectBelongsToAccount(projectId: java.util.UUID, accountId: java.util.UUID): Boolean
    fun projectAcceptsPayments(projectId: java.util.UUID): Boolean = true
    fun recordReconciliationFailure(paymentId: java.util.UUID, error: String, nextAttemptAt: java.time.Instant)
    fun releaseReconciliationClaim(paymentId: java.util.UUID)
}

data class PaymentTotals(val count: Long, val amount: java.math.BigDecimal, val succeededCount: Long, val pendingCount: Long, val failedCount: Long, val reversedCount: Long, val amountsByCurrency: Map<String, java.math.BigDecimal> = emptyMap(), val succeededByCurrency: Map<String, java.math.BigDecimal> = emptyMap())
data class PaymentStatusChange(val previousStatus: PaymentStatus?, val status: PaymentStatus, val source: String, val providerTransactionId: String?, val occurredAt: Instant)

class PaymentReconciler(private val payments: PaymentStore, private val providers: Map<String, PaymentProvider>) {
    suspend fun reconcile(before: java.time.Instant, limit: Int = 100): Int {
        return reconcile(payments.claimStalePayments(before, limit.coerceIn(1, 500)))
    }

    suspend fun reconcile(payment: Payment): Boolean = reconcile(listOf(payment)) > 0

    private suspend fun reconcile(candidates: List<Payment>): Int {
        var reconciled = 0
        for (payment in candidates) {
            if (payment.status != PaymentStatus.PENDING) continue
            val provider = providers[payment.provider] ?: run { payments.releaseReconciliationClaim(payment.id); continue }
            val verified = provider.queryStatus(payment).getOrElse { error ->
                payments.recordReconciliationFailure(payment.id, error.message ?: "Provider status query failed", Instant.now().plusSeconds(retryDelaySeconds(payment.reconciliationAttempts)))
                continue
            }
            if (verified.pending) {
                payments.recordReconciliationFailure(payment.id, "Provider still reports pending", Instant.now().plusSeconds(retryDelaySeconds(payment.reconciliationAttempts)))
                continue
            }
            val status = verified.status ?: run {
                payments.recordReconciliationFailure(payment.id, "Provider still reports pending", Instant.now().plusSeconds(retryDelaySeconds(payment.reconciliationAttempts)))
                continue
            }
            val event = ProviderPaymentEvent(payment.provider, payment.gatewayReference, status, verified.amount, verified.currency, verified.occurredAt, verified.providerTransactionId)
            if (status == PaymentStatus.PENDING) {
                payments.recordReconciliationFailure(payment.id, "Provider still reports pending", Instant.now().plusSeconds(retryDelaySeconds(payment.reconciliationAttempts)))
                continue
            }
            val record = NewPaymentEvent(payment.provider, "reconciliation.${status.name.lowercase()}", "reconciliation:${payment.provider}:${payment.providerReference}:${status.name.lowercase()}:${payment.createdAt.epochSecond}", payment.providerReference, "provider status query")
            val recorder = payments as? PaymentEventRecorder
            if (recorder == null) {
                payments.recordReconciliationFailure(payment.id, "Payment store cannot apply provider status results", Instant.now().plusSeconds(retryDelaySeconds(payment.reconciliationAttempts)))
                continue
            }
            val result = PaymentEventProcessor(recorder).process(record, event)
            if (result == EventResult.PROCESSED) reconciled++
            else if (result == EventResult.REJECTED) payments.recordReconciliationFailure(payment.id, "Provider status result did not match the payment", Instant.now().plusSeconds(retryDelaySeconds(payment.reconciliationAttempts)))
            else reconciled++
        }
        return reconciled
    }

    private fun retryDelaySeconds(attempts: Int): Long = (60L * (1L shl attempts.coerceIn(0, 8))).coerceAtMost(21600L)
}

data class PaymentReservation(val reservation: java.util.UUID?, val existing: Payment?)

data class ProviderPaymentEvent(
    val provider: String,
    val reference: String,
    val status: PaymentStatus,
    val amount: java.math.BigDecimal? = null,
    val currency: String? = null,
    val paidAt: java.time.Instant? = null,
    val providerTransactionId: String? = null
)

data class NewPaymentEvent(
    val provider: String,
    val eventType: String,
    val deduplicationKey: String,
    val reference: String?,
    val rawPayload: String
)

class UnknownPaymentAccount : IllegalArgumentException("Account not found")
