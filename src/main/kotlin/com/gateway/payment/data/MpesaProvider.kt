package com.gateway.payment.data

import com.gateway.config.GatewayConfig
import com.gateway.payment.domain.*
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.*
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

class MpesaProvider(
    private val consumerKey: String = GatewayConfig.mpesaConsumerKey,
    private val consumerSecret: String = GatewayConfig.mpesaConsumerSecret,
    private val shortcode: String = GatewayConfig.mpesaShortcode,
    private val passkey: String = GatewayConfig.mpesaPasskey,
    private val callbackToken: String = GatewayConfig.mpesaCallbackToken,
    private val client: HttpClient = HttpClient(CIO) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
        install(HttpTimeout) { requestTimeoutMillis = 15000; connectTimeoutMillis = 8000; socketTimeoutMillis = 15000 }
    }
) : PaymentProvider {
    override val name = "mpesa"
    override val capabilities = setOf(PaymentCapability.CUSTOMER_PROMPT, PaymentCapability.STATUS_QUERY, PaymentCapability.TOKEN_AUTHENTICATED_CALLBACK)
    private val baseUrl = GatewayConfig.mpesaApiBaseUrl
    private val tokenCache = AtomicReference<CachedToken?>(null)

    override suspend fun initiate(command: InitiatePaymentCommand): Result<InitiatedPayment> = runCatching {
        requireConfigured()
        require(command.currency == "KES") { "M-Pesa STK Push supports KES payments" }
        val phone = normalizePhone(requireNotNull(command.phoneNumber) { "A customer phone number is required for M-Pesa" })
        val timestamp = LocalDateTime.now(ZoneOffset.ofHours(3)).format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
        val password = Base64.getEncoder().encodeToString("$shortcode$passkey$timestamp".toByteArray())
        val token = accessToken()
        val request = StkPushRequest(
            BusinessShortCode = shortcode,
            Password = password,
            Timestamp = timestamp,
            TransactionType = GatewayConfig.mpesaTransactionType,
            Amount = command.amount.longValueExact(),
            PartyA = phone,
            PartyB = shortcode,
            PhoneNumber = phone,
            CallBackURL = "${GatewayConfig.publicBaseUrl}/api/payments/mpesa/callback?token=${java.net.URLEncoder.encode(callbackToken, Charsets.UTF_8)}",
            AccountReference = (command.gatewayReference ?: "GW").take(12),
            TransactionDesc = (command.description ?: "Gateway payment").take(13)
        )
        val response = client.post("$baseUrl/mpesa/stkpush/v1/processrequest") {
            header("Authorization", "Bearer $token"); contentType(ContentType.Application.Json); setBody(request)
        }.body<StkPushResponse>()
        check(response.ResponseCode == "0") { response.ResponseDescription ?: response.CustomerMessage ?: "M-Pesa STK Push request failed" }
        val checkoutId = requireNotNull(response.CheckoutRequestID) { "M-Pesa did not return a checkout request ID" }
        val gatewayReference = requireNotNull(command.gatewayReference) { "Gateway reference is required" }
        InitiatedPayment(gatewayReference, null, checkoutId, null, mapOf("checkoutRequestId" to checkoutId, "merchantRequestId" to response.MerchantRequestID.orEmpty()))
    }

    override suspend fun authenticateNotification(headers: Map<String, String>, rawBody: String, requestToken: String?): Result<Unit> = runCatching {
        require(callbackToken.isNotBlank()) { "MPESA_CALLBACK_TOKEN is not configured" }
        val supplied = requestToken ?: headers.entries.firstOrNull { it.key.equals("x-gateway-callback-token", true) }?.value
        require(supplied != null && java.security.MessageDigest.isEqual(callbackToken.toByteArray(), supplied.toByteArray())) { "M-Pesa callback authentication failed" }
    }

    override fun decodeNotification(rawBody: String): Result<NormalizedProviderNotification> = runCatching {
        val payload = Json { ignoreUnknownKeys = true }.decodeFromString<StkCallbackEnvelope>(rawBody)
        val callback = payload.Body.stkCallback
        val values = callback.CallbackMetadata?.Item.orEmpty().associate { it.Name.lowercase() to it.Value?.content }
        val reference = callback.CheckoutRequestID
        val transactionId = values["mpesareceiptnumber"]
        val amount = values["amount"]?.toBigDecimalOrNull()
        if (callback.ResultCode == 0) {
            require(!transactionId.isNullOrBlank()) { "Successful M-Pesa callback is missing its receipt number" }
            require(amount != null && amount > BigDecimal.ZERO) { "Successful M-Pesa callback is missing its amount" }
        }
        val status = when {
            callback.ResultCode == 0 -> PaymentStatus.SUCCEEDED
            callback.ResultCode == 1032 -> PaymentStatus.FAILED
            callback.ResultCode == 1037 -> null
            else -> PaymentStatus.FAILED
        }
        val key = "${callback.MerchantRequestID}:${callback.CheckoutRequestID}:${callback.ResultCode}"
        NormalizedProviderNotification("stk.callback", key, reference ?: payload.Body.stkCallback.CheckoutRequestID, status, amount, "KES", transactionId, Instant.now(), rawBody)
    }

    override suspend fun queryStatus(payment: Payment): Result<ProviderPaymentStatus> = runCatching {
        requireConfigured()
        val checkoutId = payment.providerRequestId?.takeIf { it.startsWith("ws_") }
            ?: payment.providerTransactionId?.takeIf { it.startsWith("ws_") }
            ?: error("M-Pesa checkout request ID is missing")
        val timestamp = LocalDateTime.now(ZoneOffset.ofHours(3)).format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
        val password = Base64.getEncoder().encodeToString("$shortcode$passkey$timestamp".toByteArray())
        val response = client.post("$baseUrl/mpesa/stkpushquery/v1/query") {
            header("Authorization", "Bearer ${accessToken()}"); contentType(ContentType.Application.Json)
            setBody(StkQueryRequest(shortcode, password, timestamp, checkoutId))
        }.body<StkQueryResponse>()
        val status = when (response.ResultCode) {
            "0" -> PaymentStatus.SUCCEEDED
            "1032", "1", "2001" -> PaymentStatus.FAILED
            "1037" -> null
            null -> null
            else -> PaymentStatus.FAILED
        }
        val transactionId = response.ResultDesc?.takeIf { it.startsWith("M-Pesa transaction completed") }?.substringAfterLast(' ')
        ProviderPaymentStatus(status, if (status == PaymentStatus.SUCCEEDED) payment.amount else null, if (status == PaymentStatus.SUCCEEDED) "KES" else null, transactionId ?: payment.providerTransactionId, pending = status == null)
    }

    private suspend fun accessToken(): String {
        val now = System.currentTimeMillis()
        tokenCache.get()?.takeIf { it.expiresAtMillis > now + 30000 }?.let { return it.value }
        val credentials = Base64.getEncoder().encodeToString("$consumerKey:$consumerSecret".toByteArray())
        synchronized(tokenCache) {
            tokenCache.get()?.takeIf { it.expiresAtMillis > System.currentTimeMillis() + 30000 }?.let { return it.value }
        }
        val response = client.get("$baseUrl/oauth/v1/generate?grant_type=client_credentials") {
            header("Authorization", "Basic $credentials")
        }.body<TokenResponse>()
        val expirySeconds = response.expires_in.toLongOrNull() ?: 3599
        synchronized(tokenCache) {
            tokenCache.get()?.takeIf { it.expiresAtMillis > System.currentTimeMillis() + 30000 }?.let { return it.value }
            tokenCache.set(CachedToken(response.access_token, System.currentTimeMillis() + expirySeconds * 1000))
        }
        return response.access_token
    }

    private fun requireConfigured() {
        require(consumerKey.isNotBlank() && consumerSecret.isNotBlank() && shortcode.isNotBlank() && passkey.isNotBlank()) { "M-Pesa Daraja credentials are not configured" }
        require(callbackToken.isNotBlank()) { "MPESA_CALLBACK_TOKEN is not configured" }
    }

    private fun normalizePhone(input: String): String {
        val digits = input.filter(Char::isDigit)
        val normalized = when {
            digits.startsWith("254") -> digits
            digits.startsWith("0") && digits.length == 10 -> "254${digits.drop(1)}"
            digits.startsWith("7") && digits.length == 9 -> "254$digits"
            digits.startsWith("1") && digits.length == 9 -> "254$digits"
            else -> digits
        }
        require(normalized.matches(Regex("254[17]\\d{8}"))) { "Enter a valid Kenyan M-Pesa phone number" }
        return normalized
    }

    fun close() = client.close()

    private data class CachedToken(val value: String, val expiresAtMillis: Long)
    @Serializable private data class TokenResponse(val access_token: String, val expires_in: String = "3599")
    @Serializable private data class StkPushRequest(val BusinessShortCode: String, val Password: String, val Timestamp: String, val TransactionType: String, val Amount: Long, val PartyA: String, val PartyB: String, val PhoneNumber: String, val CallBackURL: String, val AccountReference: String, val TransactionDesc: String)
    @Serializable private data class StkPushResponse(val ResponseCode: String, val ResponseDescription: String? = null, val CustomerMessage: String? = null, val MerchantRequestID: String? = null, val CheckoutRequestID: String? = null)
    @Serializable private data class StkQueryRequest(val BusinessShortCode: String, val Password: String, val Timestamp: String, val CheckoutRequestID: String)
    @Serializable private data class StkQueryResponse(val ResultCode: String? = null, val ResultDesc: String? = null)
    @Serializable private data class StkCallbackEnvelope(val Body: CallbackBody)
    @Serializable private data class CallbackBody(@SerialName("stkCallback") val stkCallback: StkCallback)
    @Serializable private data class StkCallback(val MerchantRequestID: String, val CheckoutRequestID: String, val ResultCode: Int, val ResultDesc: String, val CallbackMetadata: CallbackMetadata? = null)
    @Serializable private data class CallbackMetadata(val Item: List<CallbackItem> = emptyList())
    @Serializable private data class CallbackItem(val Name: String, val Value: kotlinx.serialization.json.JsonPrimitive? = null)
}
