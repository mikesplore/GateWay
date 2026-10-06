package com.gateway.payment

import com.gateway.payment.data.PaystackWebhook
import com.gateway.payment.domain.EventResult
import com.gateway.payment.domain.NewPaymentEvent
import com.gateway.payment.domain.PaymentEventProcessor
import com.gateway.payment.domain.PaymentStore
import com.gateway.payment.domain.ProviderPaymentEvent
import com.gateway.payment.domain.PaymentStateTransitions
import com.gateway.payment.domain.PaymentStatus
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PaystackWebhookTest {
    @Test
    fun `succeeded payment cannot be downgraded by delayed failure`() {
        assertFalse(PaymentStateTransitions.allows(PaymentStatus.SUCCEEDED, PaymentStatus.FAILED))
        assertTrue(PaymentStateTransitions.allows(PaymentStatus.SUCCEEDED, PaymentStatus.REVERSED))
        assertTrue(PaymentStateTransitions.allows(PaymentStatus.PENDING, PaymentStatus.SUCCEEDED))
    }

    @Test
    fun `verifies signature over exact raw body`() {
        val raw = "{\"event\":\"charge.success\"}"
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec("test-secret".toByteArray(), "HmacSHA512"))
        val signature = mac.doFinal(raw.toByteArray()).joinToString("") { "%02x".format(it) }
        assertTrue(PaystackWebhook.verify(raw, signature, "test-secret"))
        assertFalse(PaystackWebhook.verify("$raw ", signature, "test-secret"))
    }

    @Test
    fun `deduplicates repeated provider event`() {
        val seen = mutableSetOf<String>()
        val record = NewPaymentEvent("paystack", "charge.success", "charge.success:ref", "ref", "{}")
        val event = ProviderPaymentEvent("paystack", "ref", com.gateway.payment.domain.PaymentStatus.SUCCEEDED)
        val handled = mutableSetOf<String>()
        val idempotent = PaymentEventProcessor(object : com.gateway.payment.domain.PaymentEventRecorder {
            override fun processProviderNotification(incoming: NewPaymentEvent, event: ProviderPaymentEvent?) = if (handled.add(incoming.deduplicationKey)) "processed" else "duplicate"
            override fun claimFailedEvent(eventId: java.util.UUID) = null
            override fun finishReplayedEvent(eventId: java.util.UUID, event: ProviderPaymentEvent?, error: String?) = false
        })
        assertEquals(EventResult.PROCESSED, idempotent.process(record, event))
        assertEquals(EventResult.DUPLICATE, idempotent.process(record, event))
    }
}
