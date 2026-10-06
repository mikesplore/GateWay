package com.gateway.payment.data

import com.gateway.payment.domain.NewPaymentEvent
import com.gateway.payment.domain.ProviderPaymentEvent
import com.gateway.payment.domain.PaymentStatus
import com.gateway.payment.domain.NormalizedProviderNotification
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.math.BigDecimal
import java.time.Instant

object PaystackWebhook {
    private val json = Json { ignoreUnknownKeys = true }

    fun verify(rawBody: String, signature: String?, secret: String): Boolean {
        if (signature.isNullOrBlank() || secret.isBlank()) return false
        val expected = runCatching {
            val mac = Mac.getInstance("HmacSHA512")
            mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA512"))
            mac.doFinal(rawBody.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }.getOrNull() ?: return false
        if (expected.length != signature.length) return false
        return java.security.MessageDigest.isEqual(expected.toByteArray(), signature.lowercase().toByteArray())
    }

    fun decode(rawBody: String): Decoded? = runCatching {
        val payload = json.decodeFromString<Payload>(rawBody)
        val data = payload.data
        require(payload.event in setOf("charge.success", "charge.failed", "charge.reversed")) { "Unsupported Paystack event" }
        val status = when (payload.event) {
            "charge.success" -> PaymentStatus.SUCCEEDED
            "charge.failed" -> PaymentStatus.FAILED
            "charge.reversed" -> PaymentStatus.REVERSED
            else -> null
        }
        val gatewayReference = data.metadata?.gatewayReference ?: data.reference
        val key = "${payload.event}:${data.id ?: data.reference}"
        val eventRecord = NewPaymentEvent("paystack", payload.event, key, gatewayReference, rawBody)
        val providerEvent = status?.let {
            ProviderPaymentEvent("paystack", gatewayReference, it, BigDecimal.valueOf(data.amount, 2), data.currency, data.paidAt?.let { date -> runCatching { Instant.parse(date) }.getOrNull() }, data.id?.toString())
        }
        Decoded(eventRecord, providerEvent, NormalizedProviderNotification(payload.event, key, gatewayReference, status, BigDecimal.valueOf(data.amount, 2), data.currency, data.id?.toString(), data.paidAt?.let { date -> runCatching { Instant.parse(date) }.getOrNull() }, rawBody))
    }.getOrNull()

    data class Decoded(val record: NewPaymentEvent, val paymentEvent: ProviderPaymentEvent?, val notification: NormalizedProviderNotification)

    @Serializable private data class Payload(val event: String, val data: Data)
    @Serializable private data class Data(val reference: String, val status: String = "", val amount: Long, val currency: String, @SerialName("paid_at") val paidAt: String? = null, val id: Long? = null, val metadata: Metadata? = null)
    @Serializable private data class Metadata(@SerialName("gateway_reference") val gatewayReference: String? = null)
}
