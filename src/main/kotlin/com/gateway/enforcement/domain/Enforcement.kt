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

    fun localPort(port: Int): String {
        require(port in 1..65535) { "Application port must be between 1 and 65535" }
        return "http://127.0.0.1:$port"
    }

    fun tlsRef(raw: String?): String? = raw?.trim()?.takeIf(String::isNotEmpty)?.let(::hostname)
}

object NginxRenderer {
    fun render(site: SiteEnforcementConfig, certificateRoot: String = "/etc/letsencrypt/live", gatewayPort: Int = 8080): String {
        val normalizedCertificateRoot = java.nio.file.Path.of(certificateRoot).toAbsolutePath().normalize()
        require(normalizedCertificateRoot.toString() == certificateRoot && !certificateRoot.contains("..")) { "Certificate directory must be an absolute normalized path" }
        require(gatewayPort in 1..65535) { "Gateway port must be between 1 and 65535" }
        require(site.template == "proxy") { "Unsupported site template" }
        val host = SiteInput.hostname(site.hostname)
        // HTTPS is required for managed sites. A missing explicit reference uses
        // the site's hostname as the standard Let's Encrypt lineage.
        val tls = SiteInput.tlsRef(site.tlsRef) ?: host
        val action = enforcementAction(site.entitlementState)
        val upstream = site.upstreamUrl?.let(SiteInput::upstream)
        if (action == EnforcementAction.PROXY) require(upstream != null) { "An upstream URL is required before enabling a site" }
        val authLocation = "gateway-auth-${site.id}"
        val paywallLocation = "@gateway-paywall-${site.id}"
        return buildString {
            appendLine("# gateway:site:${site.id}")
            appendLine("# gateway:hostname:$host")
            appendLine("server {")
            appendLine("    listen 80;")
            appendLine("    listen [::]:80;")
            appendLine("    server_name $host;")
            appendLine("    return 301 https://\$host\$request_uri;")
            appendLine("}")
            appendLine()
            appendLine("server {")
            appendLine("    listen 443 ssl;")
            appendLine("    listen [::]:443 ssl;")
            appendLine("    http2 on;")
            appendLine("    ssl_certificate $normalizedCertificateRoot/$tls/fullchain.pem;")
            appendLine("    ssl_certificate_key $normalizedCertificateRoot/$tls/privkey.pem;")
            appendLine("    server_name $host;")
            appendLine()
            appendLine("    location / {")
            appendLine("        auth_request /$authLocation;")
            appendLine("        error_page 403 = $paywallLocation;")
            if (upstream != null) appendLine("        proxy_pass $upstream;")
            appendLine("        proxy_http_version 1.1;")
            appendLine("        proxy_set_header Upgrade \$http_upgrade;")
            appendLine("        proxy_set_header Connection \"upgrade\";")
            appendLine("        proxy_set_header Host \$host;")
            appendLine("        proxy_set_header X-Real-IP \$remote_addr;")
            appendLine("        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;")
            appendLine("        proxy_set_header X-Forwarded-Proto \$scheme;")
            if (upstream?.startsWith("https://") == true) appendLine("        proxy_ssl_server_name on;")
            appendLine("    }")
            appendLine()
            appendLine("    # Internal Nginx authorization subrequest; strip browser headers and body.")
            appendLine("    location = /$authLocation {")
            appendLine("        internal;")
            appendLine("        proxy_pass http://127.0.0.1:$gatewayPort/api/enforcement/auth?siteId=${site.id};")
            appendLine("        proxy_method GET;")
            appendLine("        proxy_pass_request_headers off;")
            appendLine("        proxy_pass_request_body off;")
            appendLine("        proxy_set_header Content-Length \"\";")
            appendLine("        proxy_set_header Content-Type \"\";")
            appendLine("        proxy_set_header Transfer-Encoding \"\";")
            appendLine("        proxy_set_header Authorization \"\";")
            appendLine("        proxy_set_header Cookie \"\";")
            appendLine("        proxy_set_header Origin \"\";")
            appendLine("        proxy_set_header Referer \"\";")
            appendLine("        proxy_set_header User-Agent \"Nginx-Auth-Check\";")
            appendLine("        proxy_set_header Accept \"\";")
            appendLine("        proxy_set_header Accept-Encoding \"\";")
            appendLine("        proxy_set_header Accept-Language \"\";")
            appendLine("        proxy_set_header Sec-Fetch-Dest \"\";")
            appendLine("        proxy_set_header Sec-Fetch-Mode \"\";")
            appendLine("        proxy_set_header Sec-Fetch-Site \"\";")
            appendLine("        proxy_set_header Sec-Ch-Ua \"\";")
            appendLine("        proxy_set_header Host 127.0.0.1;")
            appendLine("        proxy_http_version 1.1;")
            appendLine("        proxy_set_header Connection \"\";")
            appendLine("    }")
            appendLine()
            appendLine("    location $paywallLocation {")
            appendLine("        rewrite ^ /api/enforcement/paywall?siteId=${site.id} break;")
            appendLine("        proxy_pass http://127.0.0.1:$gatewayPort;")
            appendLine("        proxy_set_header Host \$host;")
            appendLine("        proxy_set_header X-Real-IP \$remote_addr;")
            appendLine("        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;")
            appendLine("        proxy_set_header X-Forwarded-Proto \$scheme;")
            appendLine("    }")
            appendLine("}")
        }
    }

    fun hash(config: String): String = MessageDigest.getInstance("SHA-256")
        .digest(config.toByteArray()).joinToString("") { "%02x".format(it) }
}
