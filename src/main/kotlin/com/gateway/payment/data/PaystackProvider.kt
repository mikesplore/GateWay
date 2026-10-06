package com.gateway.payment.data

import com.gateway.config.GatewayConfig
import com.gateway.payment.domain.InitiatePaymentCommand
import com.gateway.payment.domain.InitiatedPayment
import com.gateway.payment.domain.Payment
import com.gateway.payment.domain.PaymentStatus
import com.gateway.payment.domain.ProviderPaymentStatus
import com.gateway.payment.domain.NormalizedProviderNotification
import com.gateway.payment.domain.PaymentProvider
import com.gateway.payment.domain.PaymentCapability
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.math.BigDecimal

class PaystackProvider(
    private val secretKey: String = GatewayConfig.paystackSecretKey,
    private val client: HttpClient = HttpClient {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }
) : PaymentProvider {
    override val name = "paystack"
    override val capabilities = setOf(PaymentCapability.BROWSER_CHECKOUT, PaymentCapability.STATUS_QUERY, PaymentCapability.SIGNED_WEBHOOK)
    private val baseUrl = "https://api.paystack.co"

    override suspend fun initiate(command: InitiatePaymentCommand): Result<InitiatedPayment> = runCatching {
        require(secretKey.isNotBlank()) { "Paystack is not configured" }
        val kobo = command.amount.movePointRight(2).longValueExact()
        val response = client.post("$baseUrl/transaction/initialize") {
            header("Authorization", "Bearer $secretKey")
            contentType(ContentType.Application.Json)
            setBody(InitializeRequest(command.email, kobo, command.currency, "${GatewayConfig.publicBaseUrl}/api/payments/paystack/callback", Metadata(command.accountId.toString(), command.gatewayReference)))
        }.body<PaystackResponse<InitializeData>>()
        check(response.status && response.data != null) { response.message ?: "Paystack could not initialize payment" }
        InitiatedPayment(command.gatewayReference ?: response.data.reference, response.data.authorizationUrl, providerRequestId = response.data.reference, metadata = mapOf("gatewayReference" to command.gatewayReference.orEmpty()))
    }

    override suspend fun authenticateNotification(headers: Map<String, String>, rawBody: String, requestToken: String?): Result<Unit> =
        if (PaystackWebhook.verify(rawBody, headers.entries.firstOrNull { it.key.equals("x-paystack-signature", true) }?.value, secretKey)) Result.success(Unit)
        else Result.failure(IllegalArgumentException("Paystack signature verification failed"))

    override fun decodeNotification(rawBody: String): Result<NormalizedProviderNotification> =
        runCatching { requireNotNull(PaystackWebhook.decode(rawBody)).notification }

    override suspend fun queryStatus(payment: Payment): Result<ProviderPaymentStatus> = runCatching {
        require(secretKey.isNotBlank()) { "Paystack is not configured" }
        val safeReference = java.net.URLEncoder.encode(payment.providerRequestId ?: payment.providerReference, Charsets.UTF_8)
        val response = client.get("$baseUrl/transaction/verify/$safeReference") {
            header("Authorization", "Bearer $secretKey")
        }.body<PaystackResponse<VerifyData>>()
        check(response.status && response.data != null) { response.message ?: "Paystack verification failed" }
        val status = when (response.data.status.lowercase()) { "success" -> PaymentStatus.SUCCEEDED; "failed" -> PaymentStatus.FAILED; "reversed" -> PaymentStatus.REVERSED; "abandoned" -> PaymentStatus.EXPIRED; else -> null }
        val verifiedAmount = if (status == PaymentStatus.SUCCEEDED) BigDecimal.valueOf(response.data.amount, 2) else null
        ProviderPaymentStatus(status, verifiedAmount, verifiedAmount?.let { response.data.currency }, response.data.id?.toString(), response.data.paidAt?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }, status == null)
    }

    fun close() = client.close()

    @Serializable private data class InitializeRequest(
        val email: String,
        val amount: Long,
        val currency: String,
        @SerialName("callback_url") val callbackUrl: String?,
        val metadata: Metadata
    )
    @Serializable private data class Metadata(@SerialName("account_id") val accountId: String, @SerialName("gateway_reference") val gatewayReference: String?)
    @Serializable private data class PaystackResponse<T>(val status: Boolean, val message: String? = null, val data: T? = null)
    @Serializable private data class InitializeData(val reference: String, @SerialName("authorization_url") val authorizationUrl: String)
    @Serializable private data class VerifyData(val status: String, val amount: Long, val currency: String, val id: Long? = null, @SerialName("paid_at") val paidAt: String? = null)
}
