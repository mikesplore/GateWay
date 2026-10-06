package com.gateway.payment.domain

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

data class GatewayUser(val id: UUID, val accountId: UUID, val email: String, val displayName: String, val role: String)
data class IssuedUserSession(val user: GatewayUser, val token: String, val expiresAt: Instant)

interface HumanAuthStore {
    fun hasOwner(): Boolean
    fun provisionFirstOwner(email: String, displayName: String, passwordHash: String, accountName: String): Boolean
    fun findUserForLogin(email: String): Pair<GatewayUser, String?>?
    fun createSession(userId: UUID, tokenHash: String, expiresAt: Instant): Unit
    fun findSession(tokenHash: String): Pair<GatewayUser, Instant>?
    fun revokeSession(tokenHash: String): Unit
    fun createInvite(accountId: UUID, email: String, displayName: String, role: String, inviteHash: String, expiresAt: Instant): GatewayUser
    fun acceptInvite(inviteHash: String, passwordHash: String): GatewayUser?
}

class HumanAuthService(private val store: HumanAuthStore, private val sessionHours: Long = 12) {
    fun bootstrapOwner(email: String, displayName: String, password: String, accountName: String): Boolean {
        val normalized = normalizeEmail(email)
        require(password.length >= 12) { "Bootstrap password must be at least 12 characters" }
        require(displayName.isNotBlank() && displayName.length <= 200) { "Display name is required" }
        require(accountName.isNotBlank() && accountName.length <= 200) { "Account name is required" }
        return store.provisionFirstOwner(normalized, displayName.trim(), passwordHash(password), accountName.trim())
    }

    fun login(email: String, password: String): IssuedUserSession? {
        val record = store.findUserForLogin(normalizeEmail(email))
        val hash = record?.second
        // Keep a similar cost for unknown email to reduce account enumeration by timing.
        val valid = if (hash == null) { passwordHash("constant-time-dummy-password") ; false } else verifyPassword(password, hash)
        if (!valid || record == null) return null
        val token = randomToken()
        val expiresAt = Instant.now().plusSeconds(sessionHours * 3600)
        store.createSession(record.first.id, sha256(token), expiresAt)
        return IssuedUserSession(record.first, token, expiresAt)
    }

    fun session(token: String?): Pair<GatewayUser, Instant>? {
        if (token.isNullOrBlank() || token.length > 256) return null
        return store.findSession(sha256(token))
    }

    fun logout(token: String?) { if (!token.isNullOrBlank() && token.length <= 256) store.revokeSession(sha256(token)) }

    fun invite(accountId: UUID, email: String, displayName: String, role: String): Pair<GatewayUser, String> {
        val normalized = normalizeEmail(email)
        require(displayName.isNotBlank() && displayName.length <= 200) { "Display name is required" }
        require(role == "operator") { "Role must be operator" }
        val token = randomToken()
        return store.createInvite(accountId, normalized, displayName.trim(), role, sha256(token), Instant.now().plusSeconds(24 * 3600)) to token
    }

    fun acceptInvite(token: String, password: String): GatewayUser? {
        require(password.length >= 12) { "Password must be at least 12 characters" }
        require(password.length <= 256) { "Password is too long" }
        return store.acceptInvite(sha256(token), passwordHash(password))
    }

    private fun normalizeEmail(email: String): String {
        val normalized = email.trim().lowercase()
        require(normalized.length <= 320 && normalized.matches(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))) { "A valid email is required" }
        return normalized
    }

    private fun passwordHash(password: String): String {
        val salt = ByteArray(16).also(random::nextBytes)
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS)
        val derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return "$ITERATIONS:${Base64.getEncoder().encodeToString(salt)}:${Base64.getEncoder().encodeToString(derived)}"
    }

    private fun verifyPassword(password: String, stored: String): Boolean = runCatching {
        val parts = stored.split(':')
        require(parts.size == 3)
        val iterations = parts[0].toInt().also { require(it in 100_000..1_000_000) }
        val salt = Base64.getDecoder().decode(parts[1])
        val expected = Base64.getDecoder().decode(parts[2])
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, expected.size * 8)
        val actual = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        MessageDigest.isEqual(expected, actual)
    }.getOrDefault(false)

    private fun randomToken(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private val random = SecureRandom()
        private const val ITERATIONS = 310_000
        private const val KEY_BITS = 256
    }
}
