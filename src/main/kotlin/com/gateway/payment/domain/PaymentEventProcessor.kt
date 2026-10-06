package com.gateway.payment.domain

class PaymentEventProcessor(private val eventRecorder: PaymentEventRecorder) {
    fun process(record: NewPaymentEvent, event: ProviderPaymentEvent?): EventResult {
        return when (eventRecorder.processProviderNotification(record, event)) {
            "processed" -> EventResult.PROCESSED
            "duplicate" -> EventResult.DUPLICATE
            else -> EventResult.REJECTED
        }
    }
}

interface PaymentEventRecorder {
    fun processProviderNotification(record: NewPaymentEvent, event: ProviderPaymentEvent?): String
    fun claimFailedEvent(eventId: java.util.UUID): PaymentEvent?
    fun finishReplayedEvent(eventId: java.util.UUID, event: ProviderPaymentEvent?, error: String?): Boolean
}

enum class EventResult { PROCESSED, DUPLICATE, REJECTED }

object PaymentStateTransitions {
    fun allows(current: PaymentStatus, next: PaymentStatus): Boolean = when (current) {
        PaymentStatus.INITIALIZING -> next in setOf(PaymentStatus.INITIALIZING, PaymentStatus.PENDING, PaymentStatus.FAILED)
        PaymentStatus.PENDING -> next in setOf(PaymentStatus.PENDING, PaymentStatus.SUCCEEDED, PaymentStatus.FAILED, PaymentStatus.EXPIRED)
        PaymentStatus.FAILED -> next in setOf(PaymentStatus.FAILED, PaymentStatus.SUCCEEDED, PaymentStatus.REVERSED)
        PaymentStatus.SUCCEEDED -> next in setOf(PaymentStatus.SUCCEEDED, PaymentStatus.REVERSED)
        PaymentStatus.REVERSED -> next == PaymentStatus.REVERSED
        PaymentStatus.EXPIRED -> next in setOf(PaymentStatus.EXPIRED, PaymentStatus.SUCCEEDED)
    }
}

class ProviderEventReplayService(private val recorder: PaymentEventRecorder, private val providers: Map<String, PaymentProvider>) {
    suspend fun replay(eventId: java.util.UUID): Boolean {
        val stored = recorder.claimFailedEvent(eventId) ?: return false
        val provider = providers[stored.provider] ?: return recorder.finishReplayedEvent(eventId, null, "Provider is not configured")
        val normalized = stored.normalizedStatus?.let { status ->
            val paymentReference = stored.providerReference ?: return recorder.finishReplayedEvent(eventId, null, "Event has no payment reference")
            if (status == PaymentStatus.SUCCEEDED && (stored.normalizedAmount == null || stored.normalizedCurrency.isNullOrBlank())) return recorder.finishReplayedEvent(eventId, null, "Successful event is missing its verified amount or currency")
            ProviderPaymentEvent(stored.provider, paymentReference, status, stored.normalizedAmount, stored.normalizedCurrency, providerTransactionId = stored.providerTransactionId)
        } ?: run {
            val decoded = provider.decodeNotification(stored.rawPayload).getOrElse { error ->
                return recorder.finishReplayedEvent(eventId, null, error.message ?: "Provider event could not be decoded")
            }
            val status = decoded.status ?: return recorder.finishReplayedEvent(eventId, null, "Provider event contains no payment status")
            val reference = decoded.reference ?: return recorder.finishReplayedEvent(eventId, null, "Provider event contains no payment reference")
            ProviderPaymentEvent(stored.provider, reference, status, decoded.amount, decoded.currency, decoded.occurredAt, decoded.providerTransactionId)
        }
        return recorder.finishReplayedEvent(eventId, normalized, null)
    }
}
