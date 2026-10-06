package com.gateway.payment

import com.gateway.payment.domain.*
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PaymentReconcilerTest {
    @Test
    fun `reconciliation verifies stale pending payment and records success`() = kotlinx.coroutines.runBlocking {
        val payment = Payment(UUID.randomUUID(), UUID.randomUUID(), "fake", "ref-1", BigDecimal("12.00"), "KES", PaymentStatus.PENDING, null, Instant.now())
        val store = Store(payment)
        val provider = object : PaymentProvider {
            override val name = "fake"
            override val capabilities = emptySet<PaymentCapability>()
            override suspend fun initiate(command: InitiatePaymentCommand) = Result.failure<InitiatedPayment>(UnsupportedOperationException())
            override suspend fun authenticateNotification(headers: Map<String, String>, rawBody: String, requestToken: String?) = Result.success(Unit)
            override fun decodeNotification(rawBody: String) = Result.failure<NormalizedProviderNotification>(UnsupportedOperationException())
            override suspend fun queryStatus(payment: Payment) = Result.success(ProviderPaymentStatus(PaymentStatus.SUCCEEDED, BigDecimal("12.00"), "KES"))
        }
        assertEquals(1, PaymentReconciler(store, mapOf("fake" to provider)).reconcile(Instant.now().plusSeconds(1)))
        assertEquals(PaymentStatus.SUCCEEDED, store.applied?.status)
    }

    @Test
    fun `manual reconciliation ignores nonpending payments`() = kotlinx.coroutines.runBlocking {
        val payment = Payment(UUID.randomUUID(), UUID.randomUUID(), "fake", "ref-1", BigDecimal("12.00"), "KES", PaymentStatus.FAILED, null, Instant.now())
        val store = Store(payment)
        val provider = object : PaymentProvider {
            override val name = "fake"
            override val capabilities = emptySet<PaymentCapability>()
            override suspend fun initiate(command: InitiatePaymentCommand) = Result.failure<InitiatedPayment>(UnsupportedOperationException())
            override suspend fun authenticateNotification(headers: Map<String, String>, rawBody: String, requestToken: String?) = Result.success(Unit)
            override fun decodeNotification(rawBody: String) = Result.failure<NormalizedProviderNotification>(UnsupportedOperationException())
            override suspend fun queryStatus(payment: Payment) = Result.success(ProviderPaymentStatus(PaymentStatus.SUCCEEDED, BigDecimal("12.00"), "KES"))
        }
        assertTrue(!PaymentReconciler(store, mapOf("fake" to provider)).reconcile(payment))
        assertEquals(null, store.applied)
    }

    @Test
    fun `still pending result schedules retry and does not change payment`() = kotlinx.coroutines.runBlocking {
        val payment = Payment(UUID.randomUUID(), UUID.randomUUID(), "fake", "ref-1", BigDecimal("12.00"), "KES", PaymentStatus.PENDING, null, Instant.now())
        val store = Store(payment)
        val provider = object : PaymentProvider {
            override val name = "fake"
            override val capabilities = emptySet<PaymentCapability>()
            override suspend fun initiate(command: InitiatePaymentCommand) = Result.failure<InitiatedPayment>(UnsupportedOperationException())
            override suspend fun authenticateNotification(headers: Map<String, String>, rawBody: String, requestToken: String?) = Result.success(Unit)
            override fun decodeNotification(rawBody: String) = Result.failure<NormalizedProviderNotification>(UnsupportedOperationException())
            override suspend fun queryStatus(payment: Payment) = Result.success(ProviderPaymentStatus(PaymentStatus.PENDING, pending = true))
        }
        assertEquals(0, PaymentReconciler(store, mapOf("fake" to provider)).reconcile(Instant.now().plusSeconds(1)))
        assertEquals(null, store.applied)
        assertTrue(store.retryScheduled)
    }

    private class Store(private val payment: Payment) : PaymentStore, PaymentEventRecorder {
        var applied: ProviderPaymentEvent? = null
        var retryScheduled = false
        override fun accountExists(accountId: UUID) = true
        override fun createAccount(name: String, email: String?) = error("unused")
        override fun authenticateApiKey(apiKeyHash: String) = null
        override fun listApiKeys(accountId: UUID) = emptyList<MerchantApiKey>()
        override fun findApiKey(accountId: UUID, keyId: UUID) = null
        override fun createApiKey(accountId: UUID, name: String, prefix: String, hash: String) = error("unused")
        override fun revokeApiKey(accountId: UUID, keyId: UUID) = false
        override fun rotateApiKey(accountId: UUID, oldKeyId: UUID, name: String, prefix: String, hash: String) = error("unused")
        override fun findByGatewayReference(reference: String) = payment.takeIf { it.providerReference == reference }
        override fun findByProviderRequestId(provider: String, requestId: String) = payment.takeIf { it.provider == provider && it.providerRequestId == requestId }
        override fun findByProviderReference(provider: String, reference: String) = payment.takeIf { it.provider == provider && it.providerReference == reference }
        override fun reservePayment(accountId: UUID, amount: BigDecimal, currency: String, idempotencyKey: String, projectId: UUID?, requestEmail: String?, phoneNumber: String?, gatewayReference: String?, description: String?) = error("unused")
        override fun completeReservation(reservation: PaymentReservation, provider: String, reference: String, checkoutUrl: String?, providerRequestId: String?, gatewayReference: String) = error("unused")
        override fun listPayments(accountId: UUID, status: PaymentStatus?, provider: String?, currency: String?, projectId: UUID?, from: Instant?, to: Instant?, limit: Int, offset: Int) = emptyList<Payment>()
        override fun paymentTotals(accountId: UUID) = PaymentTotals(0, BigDecimal.ZERO, 0, 0, 0, 0)
        override fun paymentStatusHistory(paymentId: UUID) = emptyList<PaymentStatusChange>()
        override fun claimStalePayments(before: Instant, limit: Int) = listOf(payment).filter { it.createdAt.isBefore(before) }
        override fun listEvents(status: String?, limit: Int, offset: Int) = emptyList<PaymentEvent>()
        override fun claimFailedEvent(eventId: UUID) = null
        override fun finishReplayedEvent(eventId: UUID, event: ProviderPaymentEvent?, error: String?) = false
        override fun listFailedEvents(limit: Int) = emptyList<PaymentEvent>()
        override fun createSite(accountId: UUID, hostname: String) = error("unused")
        override fun listSites(accountId: UUID) = emptyList<PaymentSite>()
        override fun createProject(accountId: UUID, siteId: UUID, name: String, billingReference: String?) = error("unused")
        override fun listProjects(accountId: UUID) = emptyList<PaymentProject>()
        override fun projectBelongsToAccount(projectId: UUID, accountId: UUID) = false
        override fun recordReconciliationFailure(paymentId: UUID, error: String, nextAttemptAt: Instant) { retryScheduled = true }
        override fun releaseReconciliationClaim(paymentId: UUID) = Unit
        override fun processProviderNotification(record: NewPaymentEvent, event: ProviderPaymentEvent?): String {
            applied = event
            return if (event?.amount == payment.amount && event.currency == payment.currency) "processed" else "rejected"
        }
    }
}
