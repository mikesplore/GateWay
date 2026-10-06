package com.gateway

import com.gateway.config.GatewayConfig
import com.gateway.payment.presentation.configurePaymentRoutes
import com.gateway.plugins.configureDatabase
import com.gateway.plugins.configureMonitoring
import com.gateway.plugins.configureSerialization
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

fun main() {
    embeddedServer(Netty, host = "0.0.0.0", port = GatewayConfig.port, module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    configureSerialization()
    configureMonitoring()
    configureDatabase()
    configurePaymentRoutes()
}
