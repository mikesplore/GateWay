package com.gateway.payment

import com.gateway.payment.domain.*
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PaymentServiceTest {
    @Test
    fun `initiates payment after validating account and amount`() = kotlinx.coroutines.runBlocking {
        val store = FakeStore()
        val provider = FakeProvider()
        val result = ProviderPaymentService(store, mapOf(provider.name to provider)).initiate("test", 
            InitiatePaymentCommand(UUID.randomUUID(), "customer@example.com", BigDecimal("10.25"), "kes")
        )
        assertTrue(result.isSuccess)
        assertEquals(BigDecimal("10.25"), store.payment?.amount)
        assertEquals("KES", store.payment?.currency)
        assertEquals("test-ref", store.payment?.providerReference)
    }

    @Test
    fun `rejects invalid precision and missing account before provider call`() = kotlinx.coroutines.runBlocking {
        val store = FakeStore().apply { exists = false }
        val provider = FakeProvider()
        val service = ProviderPaymentService(store, mapOf(provider.name to provider))
        val id = UUID.randomUUID()
        assertTrue(service.initiate("test", InitiatePaymentCommand(id, "a@b.c", BigDecimal("2.999"), "KES")).isFailure)
        assertTrue(service.initiate("test", InitiatePaymentCommand(id, "a@b.c", BigDecimal("2.00"), "KES")).isFailure)
        assertEquals(0, provider.initiationCount)
    }

    @Test
    fun `rejects fractional M-Pesa KES amount before provider call`() = kotlinx.coroutines.runBlocking {
        val store = FakeStore()
        val provider = FakeProvider().apply { overrideName = "mpesa" }
        val result = ProviderPaymentService(store, mapOf(provider.name to provider)).initiate("mpesa", InitiatePaymentCommand(UUID.randomUUID(), "", BigDecimal("2.50"), "KES", phoneNumber = "+254712345678"))
        assertTrue(result.isFailure)
        assertEquals(0, provider.initiationCount)
    }

    @Test
    fun `reuses idempotent payment without calling provider twice`() = kotlinx.coroutines.runBlocking {
        val store = FakeStore()
        val provider = FakeProvider()
        val service = ProviderPaymentService(store, mapOf(provider.name to provider))
        val command = InitiatePaymentCommand(UUID.randomUUID(), "a@b.c", BigDecimal("10.25"), "KES", "order-1")
        val first = service.initiate("test", command).getOrThrow()
        val second = service.initiate("test", command).getOrThrow()
        assertEquals(first.id, second.id)
        assertEquals(1, provider.initiationCount)
    }

    @Test
    fun `rejects payment for archived project before provider call`() = kotlinx.coroutines.runBlocking {
        val store = FakeStore().apply { acceptsProjectPayments = false }
        val provider = FakeProvider()
        val result = ProviderPaymentService(store, mapOf(provider.name to provider)).initiate(
            "test", InitiatePaymentCommand(UUID.randomUUID(), "a@b.c", BigDecimal("10.00"), "KES", projectId = UUID.randomUUID())
        )
        assertTrue(result.isFailure)
        assertEquals("Archived projects cannot receive payments", result.exceptionOrNull()?.message)
        assertEquals(0, provider.initiationCount)
    }

    private class FakeProvider : PaymentProvider {
        var initiationCount = 0
        var overrideName = "test"
        override val name get() = overrideName
        override val capabilities get() = if (name == "mpesa") setOf(PaymentCapability.CUSTOMER_PROMPT) else emptySet()
        override suspend fun initiate(command: InitiatePaymentCommand): Result<InitiatedPayment> {
            initiationCount++
            return Result.success(InitiatedPayment("test-ref", "https://checkout.test"))
        }
        override suspend fun authenticateNotification(headers: Map<String, String>, rawBody: String, requestToken: String?) = Result.success(Unit)
        override fun decodeNotification(rawBody: String) = Result.failure<NormalizedProviderNotification>(UnsupportedOperationException())
        override suspend fun queryStatus(payment: Payment) = Result.success(ProviderPaymentStatus(PaymentStatus.SUCCEEDED, BigDecimal("2.00"), "KES"))
    }

    private class FakeStore : PaymentStore {
        var exists = true
        var acceptsProjectPayments = true
        var payment: Payment? = null
        private val byKey = mutableMapOf<String, Payment>()
        override fun accountExists(accountId: UUID) = exists
        override fun createAccount(name: String, email: String?) = error("unused")
        override fun authenticateApiKey(apiKeyHash: String) = null
        override fun listApiKeys(accountId: UUID) = emptyList<MerchantApiKey>()
        override fun findApiKey(accountId: UUID, keyId: UUID) = null
        override fun createApiKey(accountId: UUID, name: String, prefix: String, hash: String) = error("unused")
        override fun revokeApiKey(accountId: UUID, keyId: UUID) = false
        override fun rotateApiKey(accountId: UUID, oldKeyId: UUID, name: String, prefix: String, hash: String) = error("unused")
        override fun findByGatewayReference(reference: String) = payment?.takeIf { it.providerReference == reference }
        override fun findByProviderRequestId(provider: String, requestId: String) = payment?.takeIf { it.provider == provider && it.providerRequestId == requestId }
        override fun findByProviderReference(provider: String, reference: String) = payment?.takeIf { it.provider == provider && it.providerReference == reference }
        override fun reservePayment(accountId: UUID, amount: BigDecimal, currency: String, idempotencyKey: String, projectId: UUID?, requestEmail: String?, phoneNumber: String?, gatewayReference: String?, description: String?): PaymentReservation {
            byKey[idempotencyKey]?.let { return PaymentReservation(null, it) }
            val id = UUID.randomUUID()
            byKey[idempotencyKey] = Payment(id, accountId, "initializing", "reservation:$id", amount, currency, PaymentStatus.INITIALIZING, null, Instant.now(), gatewayReference = gatewayReference ?: "reservation:$id", requestEmail = requestEmail, customerPhone = phoneNumber, description = description)
            return PaymentReservation(id, null)
        }
        override fun completeReservation(reservation: PaymentReservation, provider: String, reference: String, checkoutUrl: String?, providerRequestId: String?, gatewayReference: String): Payment {
            val reserved = byKey.values.first { it.id == reservation.reservation }
            return reserved.copy(provider = provider, providerReference = reference, status = PaymentStatus.PENDING, checkoutUrl = checkoutUrl, providerRequestId = providerRequestId, gatewayReference = gatewayReference).also { payment = it; byKey.entries.first { entry -> entry.value.id == reservation.reservation }.setValue(it) }
        }
        override fun listPayments(accountId: UUID, status: PaymentStatus?, provider: String?, currency: String?, projectId: UUID?, from: Instant?, to: Instant?, limit: Int, offset: Int) = listOfNotNull(payment).filter { it.accountId == accountId && (status == null || it.status == status) }
        override fun paymentTotals(accountId: UUID) = PaymentTotals(0, BigDecimal.ZERO, 0, 0, 0, 0)
        override fun paymentStatusHistory(paymentId: UUID) = emptyList<PaymentStatusChange>()
        override fun claimStalePayments(before: Instant, limit: Int) = emptyList<Payment>()
        override fun listEvents(status: String?, limit: Int, offset: Int) = emptyList<PaymentEvent>()
        override fun processProviderNotification(record: NewPaymentEvent, event: ProviderPaymentEvent?) = "processed"
        override fun claimFailedEvent(eventId: UUID) = null
        override fun finishReplayedEvent(eventId: UUID, event: ProviderPaymentEvent?, error: String?) = false
        override fun listFailedEvents(limit: Int) = emptyList<PaymentEvent>()
        override fun createSite(accountId: UUID, hostname: String) = error("unused")
        override fun listSites(accountId: UUID) = emptyList<PaymentSite>()
        override fun createProject(accountId: UUID, siteId: UUID, name: String, billingReference: String?) = error("unused")
        override fun listProjects(accountId: UUID) = emptyList<PaymentProject>()
        override fun projectBelongsToAccount(projectId: UUID, accountId: UUID) = true
        override fun projectAcceptsPayments(projectId: UUID) = acceptsProjectPayments
        override fun recordReconciliationFailure(paymentId: UUID, error: String, nextAttemptAt: Instant) = Unit
        override fun releaseReconciliationClaim(paymentId: UUID) = Unit
    }
}
