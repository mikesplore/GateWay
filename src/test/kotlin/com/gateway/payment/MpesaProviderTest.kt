package com.gateway.payment

import com.gateway.payment.data.MpesaProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MpesaProviderTest {
    @Test
    fun `decodes successful STK callback into normalized payment event`() {
        val provider = MpesaProvider()
        val raw = """{"Body":{"stkCallback":{"MerchantRequestID":"mr-1","CheckoutRequestID":"ws_CO_1","ResultCode":0,"ResultDesc":"Success","CallbackMetadata":{"Item":[{"Name":"Amount","Value":125.0},{"Name":"MpesaReceiptNumber","Value":"QAB123"},{"Name":"PhoneNumber","Value":254712345678}]}}}}"""
        val notification = provider.decodeNotification(raw).getOrThrow()
        assertEquals("ws_CO_1", notification.reference)
        assertEquals("QAB123", notification.providerTransactionId)
        assertEquals("KES", notification.currency)
        assertEquals("SUCCEEDED", notification.status?.name)
    }

    @Test
    fun `rejects callback when callback token is missing`() = kotlinx.coroutines.runBlocking {
        val result = MpesaProvider(callbackToken = "test-token").authenticateNotification(emptyMap(), "{}", null)
        assertTrue(result.isFailure)
    }

    @Test
    fun `rejects malformed successful callback missing receipt metadata`() {
        val raw = """{"Body":{"stkCallback":{"MerchantRequestID":"mr-1","CheckoutRequestID":"ws_CO_1","ResultCode":0,"ResultDesc":"Success"}}}"""
        assertTrue(MpesaProvider().decodeNotification(raw).isFailure)
    }
}
