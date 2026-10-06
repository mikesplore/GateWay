package com.gateway.enforcement.adapter

import com.gateway.config.GatewayConfig
import com.gateway.enforcement.domain.EntitlementState
import com.gateway.enforcement.domain.NginxRenderer
import com.gateway.enforcement.domain.SiteInput
import com.gateway.enforcement.domain.SiteEnforcementConfig
import com.gateway.payment.domain.PaymentSite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

interface SiteEnforcementStore {
    fun findSite(siteId: UUID): PaymentSite?
    fun allSites(): List<PaymentSite>
    fun saveApplyResult(siteId: UUID, hash: String?, status: String, error: String?)
    fun expireGraceEntitlements(now: java.time.Instant): List<UUID>
}

interface NginxDriver {
    fun apply(site: SiteEnforcementConfig, rendered: String): Result<Unit>
    fun hasDrift(site: SiteEnforcementConfig, renderedHash: String): Boolean
    fun inspectExternalConfigs(): List<ExternalNginxConfig>
    fun managedOrphans(knownSiteIds: Set<UUID>): List<String>
    fun readinessError(): String?
}

@Serializable data class ExternalNginxConfig(
    val filename: String,
    val path: String,
    val serverNames: List<String>,
    val upstreamUrls: List<String>,
    val certificatePaths: List<String>,
    val managedByGateway: Boolean
)

private fun executeCommand(command: List<String>): String? = try {
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    if (process.waitFor() == 0) null else output.ifBlank { "Command exited with status ${process.exitValue()}" }
} catch (error: Exception) { error.message ?: "Unable to run command" }

fun interface CertificateProvisioner {
    fun ensure(certificateDomain: String): String?
}

class HostCertificateProvisioner(
    private val certificateRoot: File = File(GatewayConfig.nginxCertificateDirectory),
    private val helper: String = GatewayConfig.certificateHelper,
    private val email: String = GatewayConfig.certificateEmail
) : CertificateProvisioner {
    override fun ensure(certificateDomain: String): String? {
        val domain = runCatching { SiteInput.hostname(certificateDomain) }.getOrElse { return it.message }
        val certDir = File(certificateRoot, domain)
        val fullchain = File(certDir, "fullchain.pem")
        val key = File(certDir, "privkey.pem")
        if (fullchain.isFile && key.isFile) return null
        if (helper.isBlank()) return "TLS certificate is missing for $domain; install it or configure GATEWAY_CERTIFICATE_HELPER"
        if (email.isBlank() || email.length > 254 || !email.contains('@')) return "GATEWAY_CERTIFICATE_EMAIL is required to request a TLS certificate"
        if (!File(helper).isAbsolute) return "GATEWAY_CERTIFICATE_HELPER must be an absolute executable path"
        val result = executeCommand(listOf("sudo", "-n", helper, "ensure", domain, email))
        if (result != null) return "Certificate helper failed: ${result.take(2000)}"
        return if (fullchain.isFile && key.isFile) null else "Certificate helper completed but certificate files are missing for $domain"
    }
}

class HostNginxDriver(
    private val managedDirectory: File = File(GatewayConfig.nginxManagedDirectory),
    private val conflictDirectories: List<File> = GatewayConfig.nginxConflictDirectories.map(::File),
    private val certificateProvisioner: CertificateProvisioner = HostCertificateProvisioner(),
    private val nginxExecutable: String = GatewayConfig.nginxExecutable,
    private val testConfig: (File) -> String? = { executable -> executeCommand(listOf("sudo", "-n", executable.absolutePath, "-t")) },
    private val reload: () -> String? = { executeCommand(listOf("sudo", "-n", "/bin/systemctl", "reload", "nginx")) }
) : NginxDriver {
    override fun apply(site: SiteEnforcementConfig, rendered: String): Result<Unit> = synchronized(globalApplyLock) {
        runCatching {
            managedDirectory.mkdirs()
            val host = site.hostname
            val target = File(managedDirectory, "${site.id}.conf")
            site.tlsRef?.let { domain -> certificateProvisioner.ensure(domain)?.let { error(it) } }
            val conflicts = findConflicts(host, target)
            require(conflicts.isEmpty()) { "Hostname $host conflicts with ${conflicts.joinToString()}" }

            val staged = File(managedDirectory, ".gateway-stage-${site.id}-${UUID.randomUUID()}")
            val validationLink = File(managedDirectory, "gateway-stage-${UUID.randomUUID()}.conf")
            Files.writeString(staged.toPath(), rendered)
            try {
                Files.createSymbolicLink(validationLink.toPath(), staged.toPath())
                val validationError = testConfig(File(nginxExecutable))
                if (validationError != null) error("nginx -t failed: ${validationError.take(3000)}")
            } finally {
                Files.deleteIfExists(validationLink.toPath())
            }

            val backup = if (target.isFile) File(managedDirectory, ".backups/${site.id}-${System.currentTimeMillis()}-${UUID.randomUUID()}").also {
                it.parentFile.mkdirs()
                Files.copy(target.toPath(), it.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
            } else null
            try {
                atomicReplace(staged, target)
                val reloadError = reload()
                if (reloadError != null) error("Nginx reload failed: ${reloadError.take(3000)}")
            } catch (error: Throwable) {
                if (backup?.isFile == true) atomicReplace(backup, target) else Files.deleteIfExists(target.toPath())
                runCatching { reload() }
                throw error
            } finally {
                Files.deleteIfExists(staged.toPath())
            }
        }
    }

    override fun hasDrift(site: SiteEnforcementConfig, renderedHash: String): Boolean {
        if (!GatewayConfig.nginxEnabled) return false
        val file = File(managedDirectory, "${site.id}.conf")
        return !file.isFile || runCatching { NginxRenderer.hash(file.readText()) != renderedHash }.getOrDefault(true)
    }

    override fun readinessError(): String? {
        if (!GatewayConfig.nginxEnabled) return null
        if (!managedDirectory.isDirectory) return "Nginx managed include directory does not exist: ${managedDirectory.absolutePath}"
        if (!managedDirectory.canWrite()) return "Nginx managed include directory is not writable: ${managedDirectory.absolutePath}"
        return null
    }

    override fun managedOrphans(knownSiteIds: Set<UUID>): List<String> = managedDirectory.listFiles().orEmpty()
        .filter { it.isFile && it.extension == "conf" }
        .filter { file ->
            val siteId = file.name.removeSuffix(".conf").let { runCatching { UUID.fromString(it) }.getOrNull() }
            siteId == null || siteId !in knownSiteIds
        }.map { it.name }.sorted()

    override fun inspectExternalConfigs(): List<ExternalNginxConfig> = GatewayConfig.nginxInspectionDirectories.map(::File).flatMap { directory ->
        directory.listFiles().orEmpty().filter { it.isFile && !it.name.startsWith(".") && !it.name.contains(".bak-") && !it.name.contains("staged") }.mapNotNull { file ->
            val content = runCatching { file.readText() }.getOrNull() ?: return@mapNotNull null
            val names = Regex("(?m)^\\s*server_name\\s+([^;]+);").findAll(content)
                .flatMap { it.groupValues[1].split(Regex("\\s+")) }.filter { it.isNotBlank() && it != "_" }.distinct().toList()
            val upstreams = Regex("(?m)^\\s*proxy_pass\\s+(https?://[^;\\s]+)")
                .findAll(content).map { it.groupValues[1] }.distinct().toList()
            val certificates = Regex("(?m)^\\s*ssl_certificate\\s+([^;\\s]+)")
                .findAll(content).map { it.groupValues[1] }.distinct().toList()
            ExternalNginxConfig(file.name, file.absolutePath, names, upstreams, certificates, "# gateway:site:" in content)
        }
    }.distinctBy { it.path }

    private fun findConflicts(hostname: String, target: File): List<String> {
        val pattern = Regex("(?m)^\\s*server_name\\s+([^;]+);")
        val host = hostname.lowercase()
        return (managedDirectory.listFiles().orEmpty().filter { it.isFile && it.extension == "conf" } +
            conflictDirectories.flatMap { it.listFiles().orEmpty().filter { f -> f.isFile && !f.name.startsWith(".") && !f.name.contains(".bak-") } })
            .filter { it.canonicalFile != target.canonicalFile }
            .mapNotNull { file ->
                val names = runCatching { pattern.findAll(file.readText()).flatMap { it.groupValues[1].split(Regex("\\s+")) }.toList() }.getOrDefault(emptyList())
                val overlaps = names.any { it.trim().trimEnd('.').lowercase() == host ||
                    it.startsWith("*.") && host.endsWith(it.removePrefix("*")) ||
                    it.startsWith(".") && (host == it.removePrefix(".") || host.endsWith(it)) || it.startsWith("~") }
                if (overlaps) "${file.absolutePath} (${names.joinToString()})" else null
            }.distinct()
    }

    private fun atomicReplace(source: File, target: File) {
        try { Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    }

    companion object { private val globalApplyLock = Any() }
}

class NginxApplyQueue(
    private val store: SiteEnforcementStore,
    private val driver: NginxDriver,
    private val scope: CoroutineScope
) {
    private val queue = Channel<UUID>(Channel.UNLIMITED)
    private val queued = ConcurrentHashMap.newKeySet<UUID>()
    private val dirty = ConcurrentHashMap.newKeySet<UUID>()

    init { scope.launch { for (siteId in queue) runCatching { process(siteId) } } }

    fun enqueue(siteId: UUID) {
        if (!GatewayConfig.nginxEnabled) return
        if (!queued.add(siteId)) { dirty.add(siteId); return }
        queue.trySend(siteId)
    }

    fun enqueueAll() { store.allSites().forEach { enqueue(it.id) } }

    fun orphanedFiles(): List<String> = if (GatewayConfig.nginxEnabled) driver.managedOrphans(store.allSites().mapTo(mutableSetOf()) { it.id }) else emptyList()

    fun reconcile(): Int {
        if (!GatewayConfig.nginxEnabled) return 0
        val drifted = store.allSites().filter { site ->
            runCatching { val config = site.toEnforcementConfig(); val rendered = NginxRenderer.render(config, GatewayConfig.nginxCertificateDirectory); driver.hasDrift(config, NginxRenderer.hash(rendered)) }.getOrDefault(true)
        }
        drifted.forEach { enqueue(it.id) }
        return drifted.size
    }

    fun expireGrace(now: java.time.Instant = java.time.Instant.now()): Int =
        store.expireGraceEntitlements(now).onEach(::enqueue).size

    private fun process(siteId: UUID) {
        try {
            val site = store.findSite(siteId) ?: return
            val config = site.toEnforcementConfig()
            val rendered = NginxRenderer.render(config, GatewayConfig.nginxCertificateDirectory)
            val hash = NginxRenderer.hash(rendered)
            if (!driver.hasDrift(config, hash)) {
                store.saveApplyResult(siteId, hash, "applied", null)
                return
            }
            val result = driver.apply(config, rendered)
            result.fold(
                onSuccess = { store.saveApplyResult(siteId, hash, "applied", null) },
                onFailure = { store.saveApplyResult(siteId, null, "apply_failed", it.message ?: "Nginx apply failed") }
            )
        } catch (error: Exception) {
            store.saveApplyResult(siteId, null, "apply_failed", error.message ?: "Unable to render Nginx site")
        } finally {
            queued.remove(siteId)
            if (dirty.remove(siteId)) enqueue(siteId)
        }
    }
}

private fun PaymentSite.toEnforcementConfig() = SiteEnforcementConfig(
    id, hostname, upstreamUrl, tlsRef, template, EntitlementState.valueOf(entitlementState.uppercase())
)
