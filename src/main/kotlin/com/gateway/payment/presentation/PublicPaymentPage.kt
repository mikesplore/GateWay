package com.gateway.payment.presentation

object PublicPaymentPage {
    val html: String by lazy {
        PublicPaymentPage::class.java.getResourceAsStream("/public/payment-required.html")
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }
            ?: "<!doctype html><html lang=\"en\"><meta charset=\"utf-8\"><title>Payment required</title><h1>Payment required</h1><p>Project billing details are temporarily unavailable. Contact the site owner.</p></html>"
    }
}
