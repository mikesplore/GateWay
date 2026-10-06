package com.gateway.enforcement.domain

import java.net.URI
import java.security.MessageDigest
import java.util.UUID

enum class EntitlementState { ACTIVE, GRACE, SUSPENDED, DISABLED_BY_ADMIN }
enum class EnforcementAction { PROXY, PAYMENT_PAGE }

data class SiteEnforcementConfig(
    val id: UUID,
    val hostname: String,
    val upstreamUrl: String?,
    val tlsRef: String?,
    val template: String,
    val entitlementState: EntitlementState
)

fun enforcementAction(state: EntitlementState): EnforcementAction = when (state) {
    EntitlementState.ACTIVE, EntitlementState.GRACE -> EnforcementAction.PROXY
    EntitlementState.SUSPENDED, EntitlementState.DISABLED_BY_ADMIN -> EnforcementAction.PAYMENT_PAGE
}

object SiteInput {
    private val hostnameLabel = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?")

    fun hostname(raw: String): String {
        val host = raw.trim().lowercase().trimEnd('.')
        require(host.length in 1..253 && host.split('.').all(hostnameLabel::matches)) { "A valid hostname is required" }
        return host
    }

    fun upstream(raw: String): String {
        val value = raw.trim()
        val uri = runCatching { URI(value) }.getOrNull()
        require(uri != null && uri.isAbsolute && uri.scheme.lowercase() in setOf("http", "https")) {
            "Upstream must be an absolute http or https URL"
        }
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.rawPath in setOf("", "/")) {
            "Upstream URL must contain only scheme, host, and optional port"
        }
        val host = uri.host ?: throw IllegalArgumentException("Upstream URL must contain a valid host")
        val normalizedHost = host.removePrefix("[").removeSuffix("]")
        require(normalizedHost.matches(Regex("[A-Za-z0-9.:-]+"))) { "Upstream host is invalid" }
        require(uri.port in -1..65535 && uri.port != 0) { "Upstream port is invalid" }
        val renderedHost = normalizedHost.lowercase().let { if (it.contains(':')) "[$it]" else it }
        val renderedPort = if (uri.port > 0) ":${uri.port}" else ""
        return "${uri.scheme.lowercase()}://$renderedHost$renderedPort"
    }

    fun tlsRef(raw: String?): String? = raw?.trim()?.takeIf(String::isNotEmpty)?.let(::hostname)
}

object NginxRenderer {
    fun render(site: SiteEnforcementConfig, certificateRoot: String = "/etc/letsencrypt/live"): String {
        val normalizedCertificateRoot = java.nio.file.Path.of(certificateRoot).toAbsolutePath().normalize()
        require(normalizedCertificateRoot.toString() == certificateRoot && !certificateRoot.contains("..")) { "Certificate directory must be an absolute normalized path" }
        require(site.template == "proxy") { "Unsupported site template" }
        val host = SiteInput.hostname(site.hostname)
        val tls = SiteInput.tlsRef(site.tlsRef)
        val action = enforcementAction(site.entitlementState)
        val upstream = site.upstreamUrl?.let(SiteInput::upstream)
        if (action == EnforcementAction.PROXY) require(upstream != null) { "An upstream URL is required before enabling a site" }
        return buildString {
            appendLine("# gateway:site:${site.id}")
            appendLine("# gateway:hostname:$host")
            if (tls != null) {
                appendLine("server {")
                appendLine("    listen 80;")
                appendLine("    listen [::]:80;")
                appendLine("    server_name $host;")
                appendLine("    return 301 https://\$host\$request_uri;")
                appendLine("}")
                appendLine()
            }
            appendLine("server {")
            appendLine(if (tls == null) "    listen 80;" else "    listen 443 ssl;")
            if (tls == null) appendLine("    listen [::]:80;") else appendLine("    listen [::]:443 ssl;")
            if (tls != null) {
                appendLine("    ssl_certificate $normalizedCertificateRoot/$tls/fullchain.pem;")
                appendLine("    ssl_certificate_key $normalizedCertificateRoot/$tls/privkey.pem;")
            }
            appendLine("    server_name $host;")
            appendLine()
            if (action == EnforcementAction.PROXY) {
                val uri = URI(upstream!!)
                appendLine("    location / {")
                appendLine("        proxy_pass $upstream;")
                appendLine("        proxy_http_version 1.1;")
                appendLine("        proxy_set_header Host \$host;")
                appendLine("        proxy_set_header X-Real-IP \$remote_addr;")
                appendLine("        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;")
                appendLine("        proxy_set_header X-Forwarded-Proto \$scheme;")
                appendLine("        proxy_set_header Upgrade \$http_upgrade;")
                appendLine("        proxy_set_header Connection \"upgrade\";")
                if (uri.scheme == "https") appendLine("        proxy_ssl_server_name on;")
                appendLine("    }")
            } else {
                appendLine("    default_type application/json;")
                appendLine("    add_header Cache-Control \"no-store\" always;")
                appendLine("    if (\$http_accept ~* \"application/json\") {")
                appendLine("        return 402 '{\"error\":\"payment_required\",\"status\":402}';")
                appendLine("    }")
                appendLine("    location / {")
                appendLine("        default_type text/html;")
                appendLine("        return 402 '<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Payment required</title></head><body><h1>Payment required</h1><p>Access to $host is currently suspended. Contact the site owner.</p></body></html>' ;")
                appendLine("    }")
            }
            appendLine("}")
        }
    }

    fun hash(config: String): String = MessageDigest.getInstance("SHA-256")
        .digest(config.toByteArray()).joinToString("") { "%02x".format(it) }
}
