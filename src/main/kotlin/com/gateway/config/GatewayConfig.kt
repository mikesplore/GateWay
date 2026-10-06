package com.gateway.config

import io.github.cdimascio.dotenv.dotenv

object GatewayConfig {
    private val dotenv by lazy {
        dotenv {
            filename = ".env"
            ignoreIfMissing = true
        }
    }

    val port: Int = setting("PORT", "8080").toIntOrNull()?.takeIf { it in 1..65535 }
        ?: error("PORT must be between 1 and 65535")
    val databaseUrl: String = setting("DB_URL", "jdbc:postgresql://localhost:5432/gateway")
    val databaseUser: String = setting("DB_USER", "gateway")
    val databasePassword: String = setting("DB_PASSWORD", "")
    val paystackSecretKey: String = setting("PAYSTACK_SECRET_KEY", "")
    val mpesaConsumerKey: String = setting("MPESA_CONSUMER_KEY", "")
    val mpesaConsumerSecret: String = setting("MPESA_CONSUMER_SECRET", "")
    val mpesaEnvironment: String = setting("MPESA_ENVIRONMENT", "sandbox").lowercase()
    val mpesaShortcode: String = setting("MPESA_SHORTCODE", "").ifBlank { if (mpesaEnvironment == "sandbox") "174379" else "" }
    val mpesaPasskey: String = setting("MPESA_PASSKEY", "").ifBlank { if (mpesaEnvironment == "sandbox") "bfb279f9aa9bdbcf158e97dd71a467cd2e0c893059b10f78e6b72ada1ed2c919" else "" }
    val mpesaTransactionType: String = setting("MPESA_TRANSACTION_TYPE", "CustomerPayBillOnline")
    val mpesaCallbackToken: String = setting("MPESA_CALLBACK_TOKEN", "")
    val accountCreationMode: String = setting("ACCOUNT_CREATION_MODE", "disabled").lowercase()
    val opsToken: String = setting("GATEWAY_OPS_TOKEN", "")
    val bootstrapOwnerEmail: String = setting("GATEWAY_BOOTSTRAP_OWNER_EMAIL", "").trim().lowercase()
    val bootstrapOwnerPassword: String = setting("GATEWAY_BOOTSTRAP_OWNER_PASSWORD", "")
    val bootstrapOwnerName: String = setting("GATEWAY_BOOTSTRAP_OWNER_NAME", "Gateway Owner").trim()
    val bootstrapAccountName: String = setting("GATEWAY_BOOTSTRAP_ACCOUNT_NAME", "Gateway").trim()
    val authCookieSecure: Boolean = setting("AUTH_COOKIE_SECURE", "true").toBooleanStrictOrNull() ?: true
    val reconciliationBatchSize: Int = setting("RECONCILIATION_BATCH_SIZE", "100").toIntOrNull()?.coerceIn(1, 500) ?: 100
    val publicBaseUrl: String = setting("GATEWAY_PUBLIC_URL", "http://localhost:$port").trimEnd('/')
    val reconciliationIntervalSeconds: Long = setting("RECONCILIATION_INTERVAL_SECONDS", "300").toLongOrNull()?.coerceIn(30, 86400) ?: 300
    val reconciliationStaleMinutes: Long = setting("RECONCILIATION_STALE_MINUTES", "10").toLongOrNull()?.coerceIn(1, 10080) ?: 10
    val nginxEnabled: Boolean = setting("NGINX_ENABLED", "false").toBooleanStrictOrNull() ?: false
    val nginxManagedDirectory: String = setting("NGINX_MANAGED_DIRECTORY", "/etc/nginx/gateway.d")
    val nginxConflictDirectories: List<String> = setting("NGINX_CONFLICT_DIRECTORIES", "/etc/nginx/sites-enabled,/etc/nginx/conf.d")
        .split(',').map(String::trim).filter(String::isNotEmpty)
    val nginxInspectionDirectories: List<String> = setting("NGINX_INSPECTION_DIRECTORIES", "/etc/nginx/sites-available,/etc/nginx/sites-enabled,/etc/nginx/conf.d")
        .split(',').map(String::trim).filter(String::isNotEmpty)
    val nginxExecutable: String = setting("NGINX_EXECUTABLE", "/usr/sbin/nginx")
    val nginxCertificateDirectory: String = setting("NGINX_CERTIFICATE_DIRECTORY", "/etc/letsencrypt/live")
    val certificateHelper: String = setting("GATEWAY_CERTIFICATE_HELPER", "")
    val certificateEmail: String = setting("GATEWAY_CERTIFICATE_EMAIL", "")

    val mpesaApiBaseUrl: String = when (mpesaEnvironment) {
        "sandbox" -> "https://sandbox.safaricom.co.ke"
        "production" -> "https://api.safaricom.co.ke"
        else -> error("MPESA_ENVIRONMENT must be sandbox or production")
    }

    private fun setting(key: String, default: String): String =
        System.getenv(key)?.takeIf(String::isNotBlank)
            ?: dotenv[key]?.takeIf(String::isNotBlank)
            ?: default
}
